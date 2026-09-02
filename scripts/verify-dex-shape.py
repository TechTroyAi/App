#!/usr/bin/env python3
"""Verify dex properties that scripts/verify-apk.py cannot see.

This is the check that separates a *launching* Blockhold APK from one that dies in
onCreate. The v1.4.1 build committed in PR #5 passed every structural check in
verify-apk.py and still would not open, because kotlinc emitted `invokedynamic`
lambdas that `dx` silently dropped from the dex — taking MainActivity's crash
recorder with them. Nothing in the structural checks noticed.

What this asserts:

  * **class count**            — a truncated dex has fewer classes than the toolchain
                                 compiled (1074 = 64 app + 978 kotlin-stdlib + 32 annotations)
  * **call_site_id count**     — invokedynamic entries; a jump here means indy lambdas
                                 that dx had to drop
  * **startup classes present** — MainActivity and the two lambdas onCreate needs
  * **Canvas save/restore balance** — the restore-underflow crash fixed in PR #5

Usage:
    python3 scripts/verify-dex-shape.py artifacts/Blockhold-Defense-v1.4.1-installable.apk
    python3 scripts/verify-dex-shape.py classes.dex --expect-classes 1074 --expect-callsites 6

Accepts an .apk (extracts classes.dex) or a raw .dex.
"""
import io
import struct
import sys
import zipfile

# --------------------------------------------------------------- instruction sizes

# opcode -> size in 16-bit code units (0 == unused / undefined)
SIZE = [0] * 256


def _fill(lo, hi, n):
    for op in range(lo, hi + 1):
        SIZE[op] = n


_fill(0x00, 0x01, 1)   # nop, move
SIZE[0x02] = 2         # move/from16
SIZE[0x03] = 3         # move/16
SIZE[0x04] = 1         # move-wide
SIZE[0x05] = 2
SIZE[0x06] = 3
SIZE[0x07] = 1         # move-object
SIZE[0x08] = 2
SIZE[0x09] = 3
_fill(0x0A, 0x12, 1)   # move-result*, move-exception, return*, const/4
SIZE[0x13] = 2
SIZE[0x14] = 3
SIZE[0x15] = 2
SIZE[0x16] = 2
SIZE[0x17] = 3
SIZE[0x18] = 5         # const-wide (51l)
SIZE[0x19] = 2
SIZE[0x1A] = 2         # const-string (21c)
SIZE[0x1B] = 3         # const-string/jumbo (31c)
SIZE[0x1C] = 2         # const-class
_fill(0x1D, 0x1E, 1)   # monitor-enter / monitor-exit
SIZE[0x1F] = 2         # check-cast
SIZE[0x20] = 2         # instance-of
SIZE[0x21] = 1         # array-length
SIZE[0x22] = 2         # new-instance
SIZE[0x23] = 2         # new-array
SIZE[0x24] = 3         # filled-new-array (35c)
SIZE[0x25] = 3         # filled-new-array/range (3rc)
SIZE[0x26] = 3         # fill-array-data
SIZE[0x27] = 1         # throw
SIZE[0x28] = 1         # goto
SIZE[0x29] = 2
SIZE[0x2A] = 3
_fill(0x2B, 0x2C, 3)   # packed-switch / sparse-switch
_fill(0x2D, 0x31, 2)   # cmpkind
_fill(0x32, 0x37, 2)   # if-test
_fill(0x38, 0x3D, 2)   # if-testz
_fill(0x44, 0x51, 2)   # arrayop
_fill(0x52, 0x6D, 2)   # iinstanceop/iput (22c) + sget/sput (21c) — 2 code units
_fill(0x6E, 0x72, 3)   # invoke-*
_fill(0x74, 0x78, 3)   # invoke-*/range
_fill(0x7B, 0x8F, 1)   # unop
_fill(0x90, 0xAF, 2)   # binop
_fill(0xB0, 0xCF, 1)   # binop/2addr
_fill(0xD0, 0xD7, 2)   # binop/lit16
_fill(0xD8, 0xE2, 2)   # binop/lit8
SIZE[0xFA] = 4         # invoke-polymorphic (45cc)
SIZE[0xFB] = 4         # invoke-polymorphic/range (4rcc)
SIZE[0xFC] = 3         # invoke-custom (35c)
SIZE[0xFD] = 3         # invoke-custom/range (3rc)

INVOKE_KIND = set(range(0x6E, 0x73)) | set(range(0x74, 0x79)) | {0xFC, 0xFD}
TYPE_CALL_SITE_ID_ITEM = 0x0007

# (declaring class, method, expected saves, expected restores)
CANVAS_EXPECTATIONS = [
    ("drawParticle", 0, 0),
    ("drawBoard", 1, 1),
    ("drawFrame", 1, 1),
    ("drawSpriteFrameCentered", 1, 1),
    ("drawBitmapCentered", 1, 1),
]

REQUIRED_CLASSES = [
    "Lai/techtroy/blockhold/MainActivity;",
    "Lai/techtroy/blockhold/GameView;",
    # onCreate's first statement is installCrashRecorder(); its lambda must survive dexing.
    "Lai/techtroy/blockhold/MainActivity$installCrashRecorder$1;",
    "Lai/techtroy/blockhold/MainActivity$button$1$2;",
]


def uleb128(data, off):
    result = shift = 0
    while True:
        b = data[off]
        result |= (b & 0x7F) << shift
        off += 1
        if not (b & 0x80):
            return result, off
        shift += 7


class Dex:
    def __init__(self, data: bytes):
        self.data = data
        assert data[:4] == b"dex\n", "not a dex file"
        self.magic = data[:8]
        # header: magic[8] checksum[4] signature[20], then file_size at 0x20
        (self.file_size, self.header_size, self.endian_tag) = struct.unpack_from("<III", data, 0x20)
        (self.map_off,
         self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<I" + "II" * 6, data, 0x34)

        self.strings = self._read_strings()
        self.type_names = [self.strings[i] for i in self._read_type_ids()]
        self.methods = self._read_method_ids()
        self.classes = self._read_class_defs()

    def _read_strings(self):
        out = []
        for i in range(self.string_ids_size):
            off, = struct.unpack_from("<I", self.data, self.string_ids_off + i * 4)
            _, p = uleb128(self.data, off)          # utf16 length (not needed)
            out.append(self.data[p:self.data.index(b"\x00", p)].decode("utf-8", "replace"))
        return out

    def _read_type_ids(self):
        return [struct.unpack_from("<I", self.data, self.type_ids_off + i * 4)[0]
                for i in range(self.type_ids_size)]

    def _read_method_ids(self):
        return [struct.unpack_from("<HHI", self.data, self.method_ids_off + i * 8)[0:3]
                for i in range(self.method_ids_size)]

    def method_name(self, idx):
        cls, _proto, name = self.methods[idx]
        return self.type_names[cls], self.strings[name]

    def _read_class_defs(self):
        out = []
        for i in range(self.class_defs_size):
            cls, _f, _s, _i, _src, _a, data_off, _v = struct.unpack_from(
                "<8I", self.data, self.class_defs_off + i * 32)
            out.append((self.type_names[cls], data_off))
        return out

    def map_item_count(self, want_type):
        size, = struct.unpack_from("<I", self.data, self.map_off)
        for i in range(size):
            t, _u, cnt, _off = struct.unpack_from("<HHII", self.data, self.map_off + 4 + i * 12)
            if t == want_type:
                return cnt
        return 0

    def class_methods(self, type_name):
        """[(method_name, code_off)] for every method declared by type_name."""
        for cname, data_off in self.classes:
            if cname != type_name or data_off == 0:
                continue
            d, p = self.data, data_off
            counts = []
            for _ in range(4):
                v, p = uleb128(d, p)
                counts.append(v)
            n_static, n_inst, n_direct, n_virtual = counts
            for _ in range(n_static + n_inst):        # encoded_field: 2 uleb128s
                _, p = uleb128(d, p)
                _, p = uleb128(d, p)
            midx = 0
            out = []
            for _ in range(n_direct + n_virtual):
                diff, p = uleb128(d, p)
                midx += diff
                _, p = uleb128(d, p)                  # access_flags
                code_off, p = uleb128(d, p)
                out.append((self.strings[self.methods[midx][2]], code_off))
            return out
        return []

    def count_canvas_invokes(self, code_off, names):
        counts = {n: 0 for n in names}
        (regs, ins, outs, tries, debug, insns_size) = struct.unpack_from(
            "<HHHHII", self.data, code_off)
        insns = struct.unpack_from("<%dH" % insns_size, self.data, code_off + 16)
        i = 0
        while i < len(insns):
            op = insns[i] & 0xFF
            sz = SIZE[op]
            if sz == 0:
                break
            if op in INVOKE_KIND and i + 1 < len(insns):
                midx = insns[i + 1]
                if midx < len(self.methods):
                    cls, name = self.method_name(midx)
                    if cls == "Landroid/graphics/Canvas;" and name in counts:
                        counts[name] += 1
            i += sz
        return counts


def load_dex(path):
    with open(path, "rb") as f:
        head = f.read(4)
    if head == b"dex\n":
        return open(path, "rb").read()
    if head == b"PK\x03\x04":
        with zipfile.ZipFile(path) as z:
            return z.read("classes.dex")
    raise SystemExit(f"{path}: neither a dex nor an apk")


def main(argv):
    positional = [a for a in argv if not a.startswith("--")]
    opts = {}
    for a in argv:
        if a.startswith("--expect-classes="):
            opts["classes"] = int(a.split("=", 1)[1])
        elif a.startswith("--expect-callsites="):
            opts["callsites"] = int(a.split("=", 1)[1])
    if not positional:
        print(__doc__)
        return 2

    dex = Dex(load_dex(positional[0]))
    classes = dex.class_defs_size
    callsites = dex.map_item_count(TYPE_CALL_SITE_ID_ITEM)
    failures = []

    print(f"dex magic      : {dex.magic!r}")
    print(f"dex file size  : {dex.file_size}")
    print(f"class count    : {classes}")
    print(f"call_site_ids  : {callsites}")
    print()

    have = {c for c, _ in dex.classes}
    for r in REQUIRED_CLASSES:
        ok = r in have
        print(f"  {'OK     ' if ok else 'MISSING'}  {r}")
        if not ok:
            failures.append(f"missing class {r}")
    print()

    print(f"  {'function':32} {'save':>6} {'restore':>8}   expected")
    for mname, want_save, want_restore in CANVAS_EXPECTATIONS:
        got_save = got_restore = 0
        for nm, code_off in dex.class_methods("Lai/techtroy/blockhold/GameView;"):
            if nm != mname or code_off == 0:
                continue
            c = dex.count_canvas_invokes(code_off, ("save", "restore"))
            got_save += c["save"]
            got_restore += c["restore"]
        ok = got_save == want_save and got_restore == want_restore
        print(f"  {mname:32} {got_save:>6} {got_restore:>8}   "
              f"{want_save}/{want_restore} {'OK' if ok else 'MISMATCH'}")
        if not ok:
            failures.append(f"{mname}: save={got_save} restore={got_restore}, "
                            f"expected {want_save}/{want_restore}")
    print()

    if "classes" in opts:
        ok = classes == opts["classes"]
        print(f"  class count == {opts['classes']}: {'OK' if ok else 'MISMATCH'}")
        if not ok:
            failures.append(f"class count {classes} != {opts['classes']}")
    if "callsites" in opts:
        ok = callsites == opts["callsites"]
        print(f"  call sites == {opts['callsites']}: {'OK' if ok else 'MISMATCH'}")
        if not ok:
            failures.append(f"call sites {callsites} != {opts['callsites']}")
    print()

    if failures:
        print("FAIL:")
        for f in failures:
            print("  -", f)
        print()
        print("A short class count or a missing lambda class usually means kotlinc emitted")
        print("invokedynamic lambdas that dx dropped. Recompile with:")
        print("    kotlinc -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class ...")
        return 1

    print("ALL DEX SHAPE CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
