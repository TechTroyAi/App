/* Compact Python 3 subset interpreter for Troy Python */
(function (global) {
  "use strict";

  function PyError(msg, line) {
    this.message = msg;
    this.line = line || 0;
    this.name = "PyError";
  }
  PyError.prototype = Object.create(Error.prototype);

  var KEYWORDS = {
    False: 1, None: 1, True: 1, and: 1, as: 1, assert: 1, break: 1, class: 1,
    continue: 1, def: 1, del: 1, elif: 1, else: 1, except: 1, for: 1, from: 1,
    global: 1, if: 1, import: 1, in: 1, is: 1, lambda: 1, not: 1, or: 1,
    pass: 1, raise: 1, return: 1, try: 1, while: 1, with: 1, yield: 1
  };

  function tokenize(src) {
    var tokens = [];
    var i = 0, n = src.length, line = 1, col = 1;
    var indentStack = [0];
    var atLineStart = true;
    function push(type, value, l, c) {
      tokens.push({ type: type, value: value, line: l, col: c });
    }
    while (i < n) {
      if (atLineStart) {
        var spaces = 0;
        while (i < n && (src[i] === " " || src[i] === "\t")) {
          spaces += src[i] === "\t" ? 4 : 1;
          i++; col++;
        }
        if (i < n && src[i] === "#") {
          while (i < n && src[i] !== "\n") i++;
        }
        if (i < n && src[i] === "\n") {
          i++; line++; col = 1; continue;
        }
        var cur = indentStack[indentStack.length - 1];
        if (spaces > cur) {
          indentStack.push(spaces);
          push("INDENT", spaces, line, 1);
        } else {
          while (spaces < indentStack[indentStack.length - 1]) {
            indentStack.pop();
            push("DEDENT", 0, line, 1);
          }
          if (spaces !== indentStack[indentStack.length - 1]) {
            throw new PyError("IndentationError", line);
          }
        }
        atLineStart = false;
      }
      if (i >= n) break;
      var ch = src[i];
      var sl = line, sc = col;
      if (ch === " " || ch === "\t" || ch === "\r") { i++; col++; continue; }
      if (ch === "#") {
        while (i < n && src[i] !== "\n") { i++; }
        continue;
      }
      if (ch === "\n") {
        push("NEWLINE", "\n", sl, sc);
        i++; line++; col = 1; atLineStart = true;
        continue;
      }
      if (ch === '"' || ch === "'") {
        var q = ch, raw = "";
        i++; col++;
        if (src[i] === q && src[i + 1] === q) {
          i += 2; col += 2;
          while (i < n && !(src[i] === q && src[i + 1] === q && src[i + 2] === q)) {
            if (src[i] === "\n") { line++; col = 1; } else col++;
            raw += src[i++];
          }
          i += 3; col += 3;
        } else {
          while (i < n && src[i] !== q) {
            if (src[i] === "\\") { raw += src[i] + (src[i + 1] || ""); i += 2; col += 2; }
            else {
              if (src[i] === "\n") throw new PyError("unterminated string", sl);
              raw += src[i++]; col++;
            }
          }
          i++; col++;
        }
        push("STRING", JSON.parse('"' + raw.replace(/\\'/g, "'").replace(/"/g, '\\"').replace(/\n/g, "\\n") + '"'), sl, sc);
        continue;
      }
      if (ch === "f" && (src[i + 1] === '"' || src[i + 1] === "'")) {
        i++; col++;
        continue;
      }
      if (/[0-9]/.test(ch) || (ch === "." && /[0-9]/.test(src[i + 1] || ""))) {
        var num = "";
        while (i < n && /[0-9._]/.test(src[i])) { num += src[i++]; col++; }
        if (src[i] === "." || src[i] === "e" || src[i] === "E") {
          num += src[i++]; col++;
          while (i < n && /[0-9+\-eE]/.test(src[i])) { num += src[i++]; col++; }
        }
        push("NUMBER", Number(num.replace(/_/g, "")), sl, sc);
        continue;
      }
      if (/[A-Za-z_]/.test(ch)) {
        var id = "";
        while (i < n && /[A-Za-z0-9_]/.test(src[i])) { id += src[i++]; col++; }
        if (KEYWORDS[id]) push("KW", id, sl, sc);
        else push("NAME", id, sl, sc);
        continue;
      }
      var two = src.slice(i, i + 2);
      var ops2 = ["==", "!=", "<=", ">=", "**", "//", "+=", "-=", "*=", "/=", "%=", "->"];
      if (ops2.indexOf(two) >= 0) {
        push("OP", two, sl, sc); i += 2; col += 2; continue;
      }
      if ("()[]{}:,.+-*/%=<>@|&^~".indexOf(ch) >= 0) {
        push("OP", ch, sl, sc); i++; col++; continue;
      }
      throw new PyError("unexpected character " + ch, sl);
    }
    push("NEWLINE", "\n", line, col);
    while (indentStack.length > 1) {
      indentStack.pop();
      push("DEDENT", 0, line, col);
    }
    push("EOF", "", line, col);
    return tokens;
  }

  function Parser(tokens) {
    this.t = tokens;
    this.i = 0;
  }
  Parser.prototype.peek = function () { return this.t[this.i]; };
  Parser.prototype.eat = function (type, value) {
    var p = this.peek();
    if (type && p.type !== type) throw new PyError("expected " + type, p.line);
    if (value !== undefined && p.value !== value) throw new PyError("expected " + value, p.line);
    this.i++;
    return p;
  };
  Parser.prototype.match = function (type, value) {
    var p = this.peek();
    if (p.type !== type) return false;
    if (value !== undefined && p.value !== value) return false;
    this.i++;
    return p;
  };
  Parser.prototype.skipNewlines = function () {
    while (this.peek().type === "NEWLINE") this.i++;
  };
  Parser.prototype.parse = function () {
    var body = [];
    this.skipNewlines();
    while (this.peek().type !== "EOF") {
      body.push(this.stmt());
      this.skipNewlines();
    }
    return { type: "Module", body: body };
  };
  Parser.prototype.block = function () {
    this.eat("OP", ":");
    this.match("NEWLINE");
    this.eat("INDENT");
    var body = [];
    while (this.peek().type !== "DEDENT" && this.peek().type !== "EOF") {
      if (this.peek().type === "NEWLINE") { this.i++; continue; }
      body.push(this.stmt());
    }
    this.eat("DEDENT");
    return body;
  };
  Parser.prototype.stmt = function () {
    var p = this.peek();
    if (p.type === "KW") {
      if (p.value === "if") return this.ifStmt();
      if (p.value === "while") return this.whileStmt();
      if (p.value === "for") return this.forStmt();
      if (p.value === "def") return this.defStmt();
      if (p.value === "class") return this.classStmt();
      if (p.value === "return") { this.i++; var e = this.peek().type === "NEWLINE" ? null : this.expr(); this.match("NEWLINE"); return { type: "Return", value: e, line: p.line }; }
      if (p.value === "break") { this.i++; this.match("NEWLINE"); return { type: "Break", line: p.line }; }
      if (p.value === "continue") { this.i++; this.match("NEWLINE"); return { type: "Continue", line: p.line }; }
      if (p.value === "pass") { this.i++; this.match("NEWLINE"); return { type: "Pass", line: p.line }; }
      if (p.value === "import") return this.importStmt();
      if (p.value === "from") return this.fromStmt();
      if (p.value === "global") {
        this.i++;
        var names = [];
        names.push(this.eat("NAME").value);
        while (this.match("OP", ",")) names.push(this.eat("NAME").value);
        this.match("NEWLINE");
        return { type: "Global", names: names, line: p.line };
      }
      if (p.value === "assert") {
        this.i++;
        var cond = this.expr();
        this.match("NEWLINE");
        return { type: "Assert", test: cond, line: p.line };
      }
      if (p.value === "try") return this.tryStmt();
      if (p.value === "raise") {
        this.i++;
        var msg = this.peek().type === "NEWLINE" ? { type: "Str", value: "error" } : this.expr();
        this.match("NEWLINE");
        return { type: "Raise", value: msg, line: p.line };
      }
    }
    var left = this.expr();
    if (this.match("OP", "=")) {
      var right = this.expr();
      this.match("NEWLINE");
      return { type: "Assign", target: left, value: right, line: p.line };
    }
    var aug = this.peek();
    if (aug.type === "OP" && /=$/.test(aug.value) && aug.value.length === 2) {
      this.i++;
      var rv = this.expr();
      this.match("NEWLINE");
      return { type: "AugAssign", target: left, op: aug.value[0], value: rv, line: p.line };
    }
    this.match("NEWLINE");
    return { type: "Expr", value: left, line: p.line };
  };
  Parser.prototype.ifStmt = function () {
    var line = this.eat("KW", "if").line;
    var test = this.expr();
    var body = this.block();
    var orelse = [];
    if (this.match("KW", "elif")) {
      this.i--;
      this.t[this.i] = { type: "KW", value: "if", line: this.peek().line, col: 1 };
      orelse = [this.ifStmt()];
    } else if (this.match("KW", "else")) {
      orelse = this.block();
    }
    return { type: "If", test: test, body: body, orelse: orelse, line: line };
  };
  Parser.prototype.whileStmt = function () {
    var line = this.eat("KW", "while").line;
    var test = this.expr();
    var body = this.block();
    return { type: "While", test: test, body: body, line: line };
  };
  Parser.prototype.forStmt = function () {
    var line = this.eat("KW", "for").line;
    var target = this.eat("NAME").value;
    this.eat("KW", "in");
    var iter = this.expr();
    var body = this.block();
    return { type: "For", target: target, iter: iter, body: body, line: line };
  };
  Parser.prototype.defStmt = function () {
    var line = this.eat("KW", "def").line;
    var name = this.eat("NAME").value;
    this.eat("OP", "(");
    var args = [];
    if (!this.match("OP", ")")) {
      do {
        var an = this.eat("NAME").value;
        var defv = null;
        if (this.match("OP", "=")) defv = this.expr();
        args.push({ name: an, def: defv });
      } while (this.match("OP", ","));
      this.eat("OP", ")");
    }
    var body = this.block();
    return { type: "FunctionDef", name: name, args: args, body: body, line: line };
  };
  Parser.prototype.classStmt = function () {
    var line = this.eat("KW", "class").line;
    var name = this.eat("NAME").value;
    if (this.match("OP", "(")) {
      while (!this.match("OP", ")")) { this.expr(); this.match("OP", ","); }
    }
    var body = this.block();
    return { type: "ClassDef", name: name, body: body, line: line };
  };
  Parser.prototype.tryStmt = function () {
    var line = this.eat("KW", "try").line;
    var body = this.block();
    this.eat("KW", "except");
    if (this.peek().type === "NAME" || this.peek().type === "KW") this.i++;
    if (this.match("KW", "as")) this.eat("NAME");
    var handlers = this.block();
    return { type: "Try", body: body, handlers: handlers, line: line };
  };
  Parser.prototype.importStmt = function () {
    var line = this.eat("KW", "import").line;
    var name = this.eat("NAME").value;
    this.match("NEWLINE");
    return { type: "Import", name: name, line: line };
  };
  Parser.prototype.fromStmt = function () {
    var line = this.eat("KW", "from").line;
    var mod = this.eat("NAME").value;
    this.eat("KW", "import");
    var names = [this.peek().type === "OP" && this.peek().value === "*" ? (this.i++, "*") : this.eat("NAME").value];
    while (this.match("OP", ",")) names.push(this.eat("NAME").value);
    this.match("NEWLINE");
    return { type: "ImportFrom", mod: mod, names: names, line: line };
  };
  Parser.prototype.expr = function () { return this.orExpr(); };
  Parser.prototype.orExpr = function () {
    var left = this.andExpr();
    while (this.match("KW", "or")) {
      left = { type: "BoolOp", op: "or", left: left, right: this.andExpr() };
    }
    return left;
  };
  Parser.prototype.andExpr = function () {
    var left = this.notExpr();
    while (this.match("KW", "and")) {
      left = { type: "BoolOp", op: "and", left: left, right: this.notExpr() };
    }
    return left;
  };
  Parser.prototype.notExpr = function () {
    if (this.match("KW", "not")) return { type: "UnaryOp", op: "not", operand: this.notExpr() };
    return this.cmpExpr();
  };
  Parser.prototype.cmpExpr = function () {
    var left = this.arith();
    var p = this.peek();
    if ((p.type === "OP" && ["==", "!=", "<", ">", "<=", ">="].indexOf(p.value) >= 0) ||
        (p.type === "KW" && (p.value === "in" || p.value === "is"))) {
      this.i++;
      var op = p.value;
      if (op === "is" && this.match("KW", "not")) op = "is not";
      if (op === "not") { /* handled */ }
      left = { type: "Compare", op: op, left: left, right: this.arith() };
    }
    return left;
  };
  Parser.prototype.arith = function () {
    var left = this.term();
    while (this.peek().type === "OP" && (this.peek().value === "+" || this.peek().value === "-")) {
      var op = this.eat("OP").value;
      left = { type: "BinOp", op: op, left: left, right: this.term() };
    }
    return left;
  };
  Parser.prototype.term = function () {
    var left = this.power();
    while (this.peek().type === "OP" && ["*", "/", "%", "//"].indexOf(this.peek().value) >= 0) {
      var op = this.eat("OP").value;
      left = { type: "BinOp", op: op, left: left, right: this.power() };
    }
    return left;
  };
  Parser.prototype.power = function () {
    var left = this.factor();
    if (this.match("OP", "**")) left = { type: "BinOp", op: "**", left: left, right: this.power() };
    return left;
  };
  Parser.prototype.factor = function () {
    if (this.match("OP", "-")) return { type: "UnaryOp", op: "-", operand: this.factor() };
    if (this.match("OP", "+")) return this.factor();
    return this.atom();
  };
  Parser.prototype.atom = function () {
    var p = this.peek();
    var node;
    if (p.type === "NUMBER") { this.i++; node = { type: "Num", value: p.value }; }
    else if (p.type === "STRING") { this.i++; node = { type: "Str", value: p.value }; }
    else if (p.type === "KW" && (p.value === "True" || p.value === "False" || p.value === "None")) {
      this.i++;
      node = { type: "Const", value: p.value === "True" ? true : p.value === "False" ? false : null };
    }
    else if (p.type === "NAME") { this.i++; node = { type: "Name", id: p.value }; }
    else if (this.match("OP", "(")) {
      if (this.match("OP", ")")) node = { type: "Tuple", elts: [] };
      else {
        var first = this.expr();
        if (this.match("OP", ",")) {
          var elts = [first];
          if (!this.match("OP", ")")) {
            do { elts.push(this.expr()); } while (this.match("OP", ","));
            this.eat("OP", ")");
          }
          node = { type: "Tuple", elts: elts };
        } else {
          this.eat("OP", ")");
          node = first;
        }
      }
    } else if (this.match("OP", "[")) {
      var items = [];
      if (!this.match("OP", "]")) {
        do { items.push(this.expr()); } while (this.match("OP", ","));
        this.eat("OP", "]");
      }
      node = { type: "List", elts: items };
    } else if (this.match("OP", "{")) {
      var keys = [], vals = [];
      if (!this.match("OP", "}")) {
        do {
          var k = this.expr();
          this.eat("OP", ":");
          keys.push(k);
          vals.push(this.expr());
        } while (this.match("OP", ","));
        this.eat("OP", "}");
      }
      node = { type: "Dict", keys: keys, values: vals };
    } else if (this.match("KW", "lambda")) {
      var an = this.eat("NAME").value;
      this.eat("OP", ":");
      node = { type: "Lambda", arg: an, body: this.expr() };
    } else {
      throw new PyError("unexpected token " + p.type + " " + p.value, p.line);
    }
    while (true) {
      if (this.match("OP", "(")) {
        var args = [], kwargs = {};
        if (!this.match("OP", ")")) {
          do {
            if (this.peek().type === "NAME" && this.t[this.i + 1] && this.t[this.i + 1].value === "=") {
              var kn = this.eat("NAME").value;
              this.eat("OP", "=");
              kwargs[kn] = this.expr();
            } else args.push(this.expr());
          } while (this.match("OP", ","));
          this.eat("OP", ")");
        }
        node = { type: "Call", func: node, args: args, kwargs: kwargs };
      } else if (this.match("OP", "[")) {
        var a = this.expr();
        if (this.match("OP", ":")) {
          var b = this.peek().value === "]" ? null : this.expr();
          this.eat("OP", "]");
          node = { type: "Slice", value: node, lower: a, upper: b };
        } else {
          this.eat("OP", "]");
          node = { type: "Subscript", value: node, index: a };
        }
      } else if (this.match("OP", ".")) {
        var attr = this.eat("NAME").value;
        node = { type: "Attribute", value: node, attr: attr };
      } else break;
    }
    return node;
  };

  function truthy(v) {
    if (v == null || v === false) return false;
    if (typeof v === "number") return v !== 0;
    if (typeof v === "string") return v.length > 0;
    if (Array.isArray(v)) return v.length > 0;
    if (typeof v === "object" && v.__bool__) return v.__bool__();
    return true;
  }
  function pyStr(v) {
    if (v === null || v === undefined) return "None";
    if (v === true) return "True";
    if (v === false) return "False";
    if (typeof v === "string") return v;
    if (typeof v === "number") return String(v);
    if (Array.isArray(v)) return "[" + v.map(function (x) { return repr(x); }).join(", ") + "]";
    if (v && v.__str__) return v.__str__();
    if (typeof v === "object") {
      var parts = [];
      for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k) && k[0] !== "_") parts.push(repr(k) + ": " + repr(v[k]));
      return "{" + parts.join(", ") + "}";
    }
    return String(v);
  }
  function repr(v) {
    if (typeof v === "string") return JSON.stringify(v);
    return pyStr(v);
  }

  function Frame(parent) {
    this.parent = parent;
    this.locals = Object.create(null);
    this.globalsMark = Object.create(null);
  }
  Frame.prototype.get = function (name) {
    if (Object.prototype.hasOwnProperty.call(this.locals, name)) return this.locals[name];
    if (this.parent) return this.parent.get(name);
    throw new PyError("NameError: name '" + name + "' is not defined");
  };
  Frame.prototype.set = function (name, val) {
    var f = this;
    if (this.globalsMark[name]) {
      while (f.parent) f = f.parent;
    }
    f.locals[name] = val;
  };

  function Interpreter(io) {
    this.io = io || { print: function () {}, input: function () { return ""; } };
    this.stopped = false;
    this.loopGuard = 0;
  }
  Interpreter.prototype.run = function (src) {
    this.stopped = false;
    this.loopGuard = 0;
    var ast = new Parser(tokenize(src)).parse();
    var env = new Frame(null);
    this.installBuiltins(env);
    try {
      this.execBlock(ast.body, env);
    } catch (e) {
      if (e && e.type === "Return") return e.value;
      throw e;
    }
  };
  Interpreter.prototype.installBuiltins = function (env) {
    var io = this.io;
    var self = this;
    env.locals.print = function () {
      var args = [].slice.call(arguments);
      var sep = " ", end = "\n";
      io.print(args.map(pyStr).join(sep) + end);
      return null;
    };
    env.locals.input = function (prompt) {
      return io.input(prompt == null ? "" : pyStr(prompt));
    };
    env.locals.len = function (x) {
      if (x == null) return 0;
      if (typeof x === "string" || Array.isArray(x)) return x.length;
      return Object.keys(x).length;
    };
    env.locals.str = function (x) { return pyStr(x); };
    env.locals.int = function (x) { return parseInt(x, 10) || 0; };
    env.locals.float = function (x) { return parseFloat(x) || 0; };
    env.locals.list = function (x) {
      if (x == null) return [];
      if (Array.isArray(x)) return x.slice();
      if (typeof x === "string") return x.split("");
      var o = [];
      for (var k in x) o.push(k);
      return o;
    };
    env.locals.range = function (a, b, c) {
      var start = 0, stop = a, step = 1;
      if (b !== undefined) { start = a; stop = b; }
      if (c !== undefined) step = c;
      var r = [];
      if (step === 0) throw new PyError("range step 0");
      if (step > 0) for (var i = start; i < stop; i += step) r.push(i);
      else for (var j = start; j > stop; j += step) r.push(j);
      return r;
    };
    env.locals.enumerate = function (seq) {
      var out = [];
      for (var i = 0; i < seq.length; i++) out.push([i, seq[i]]);
      return out;
    };
    env.locals.zip = function () {
      var arrs = [].slice.call(arguments);
      var n = Math.min.apply(null, arrs.map(function (a) { return a.length; }));
      var o = [];
      for (var i = 0; i < n; i++) o.push(arrs.map(function (a) { return a[i]; }));
      return o;
    };
    env.locals.min = function () {
      var a = arguments.length === 1 && Array.isArray(arguments[0]) ? arguments[0] : [].slice.call(arguments);
      return Math.min.apply(null, a);
    };
    env.locals.max = function () {
      var a = arguments.length === 1 && Array.isArray(arguments[0]) ? arguments[0] : [].slice.call(arguments);
      return Math.max.apply(null, a);
    };
    env.locals.sum = function (a) { return a.reduce(function (s, x) { return s + x; }, 0); };
    env.locals.abs = Math.abs;
    env.locals.round = function (x, n) {
      if (n == null) return Math.round(x);
      var p = Math.pow(10, n);
      return Math.round(x * p) / p;
    };
    env.locals.sorted = function (a) { return a.slice().sort(function (x, y) { return x > y ? 1 : x < y ? -1 : 0; }); };
    env.locals.reversed = function (a) { return a.slice().reverse(); };
    env.locals.type = function (x) {
      if (x === null) return "NoneType";
      if (Array.isArray(x)) return "list";
      return typeof x;
    };
    env.locals.bool = function (x) { return truthy(x); };
    env.locals.isinstance = function () { return true; };
    env.locals.dict = function () { return {}; };
    env.locals.tuple = function (x) { return env.locals.list(x); };
    env.locals.pow = Math.pow;
    env.locals.math = {
      pi: Math.PI, e: Math.E, tau: Math.PI * 2, inf: Infinity,
      sqrt: Math.sqrt, sin: Math.sin, cos: Math.cos, tan: Math.tan,
      floor: Math.floor, ceil: Math.ceil, log: Math.log, exp: Math.exp,
      fabs: Math.abs, pow: Math.pow, radians: function (d) { return d * Math.PI / 180; },
      degrees: function (r) { return r * 180 / Math.PI; }
    };
    env.locals.random = {
      random: Math.random,
      randint: function (a, b) { return a + Math.floor(Math.random() * (b - a + 1)); },
      choice: function (seq) { return seq[Math.floor(Math.random() * seq.length)]; },
      shuffle: function (seq) {
        for (var i = seq.length - 1; i > 0; i--) {
          var j = Math.floor(Math.random() * (i + 1));
          var t = seq[i]; seq[i] = seq[j]; seq[j] = t;
        }
        return seq;
      }
    };
    env.locals.time = { time: function () { return Date.now() / 1000; }, sleep: function () {} };
    env.locals.json = {
      dumps: function (x) { return JSON.stringify(x); },
      loads: function (s) { return JSON.parse(s); }
    };
    env.locals.sys = { version: "Jadex 3.12-compat", platform: "android", argv: ["jadex"] };
    env.locals.os = {
      path: {
        join: function () { return [].slice.call(arguments).join("/"); },
        basename: function (p) { return String(p).split("/").pop(); }
      }
    };
  };
  Interpreter.prototype.loadModule = function (name, env, line) {
    try {
      var existing = env.get(name);
      if (existing && typeof existing === "object") return existing;
    } catch (e) {}
    var files = (this.io && this.io.files) || {};
    var src = files[name + ".py"] || files[name];
    if (src == null) throw new PyError("No module named '" + name + "'", line);
    var child = new Frame(null);
    this.installBuiltins(child);
    var ast = new Parser(tokenize(src)).parse();
    this.execBlock(ast.body, child);
    env.set(name, child.locals);
    return child.locals;
  };
  Interpreter.prototype.execBlock = function (body, env) {
    var last = null;
    for (var i = 0; i < body.length; i++) {
      if (this.stopped) throw new PyError("KeyboardInterrupt");
      last = this.exec(body[i], env);
    }
    return last;
  };
  Interpreter.prototype.exec = function (node, env) {
    switch (node.type) {
      case "Assign":
        this.assign(node.target, this.eval(node.value, env), env);
        return null;
      case "AugAssign": {
        var cur = this.eval(node.target, env);
        var add = this.eval(node.value, env);
        var nv;
        if (node.op === "+") nv = cur + add;
        else if (node.op === "-") nv = cur - add;
        else if (node.op === "*") nv = cur * add;
        else if (node.op === "/") nv = cur / add;
        else nv = cur % add;
        this.assign(node.target, nv, env);
        return null;
      }
      case "Expr": return this.eval(node.value, env);
      case "If":
        if (truthy(this.eval(node.test, env))) return this.execBlock(node.body, env);
        if (node.orelse.length) return this.execBlock(node.orelse, env);
        return null;
      case "While":
        while (truthy(this.eval(node.test, env))) {
          if (++this.loopGuard > 200000) throw new PyError("infinite loop guard", node.line);
          try { this.execBlock(node.body, env); }
          catch (e) {
            if (e && e.type === "Break") break;
            if (e && e.type === "Continue") continue;
            throw e;
          }
        }
        return null;
      case "For": {
        var iter = this.eval(node.iter, env);
        if (!Array.isArray(iter)) {
          if (typeof iter === "string") iter = iter.split("");
          else throw new PyError("not iterable", node.line);
        }
        for (var i = 0; i < iter.length; i++) {
          env.set(node.target, iter[i]);
          try { this.execBlock(node.body, env); }
          catch (e) {
            if (e && e.type === "Break") break;
            if (e && e.type === "Continue") continue;
            throw e;
          }
        }
        return null;
      }
      case "FunctionDef": {
        var fnNode = node, self = this;
        var fn = function () {
          var args = arguments;
          var frame = new Frame(env);
          for (var i = 0; i < fnNode.args.length; i++) {
            var spec = fnNode.args[i];
            var val = i < args.length ? args[i] : (spec.def ? self.eval(spec.def, env) : null);
            frame.locals[spec.name] = val;
          }
          try { return self.execBlock(fnNode.body, frame); }
          catch (e) {
            if (e && e.type === "Return") return e.value;
            throw e;
          }
        };
        fn.__name__ = node.name;
        env.set(node.name, fn);
        return null;
      }
      case "ClassDef": {
        var cls = { __name__: node.name };
        var cenv = new Frame(env);
        this.execBlock(node.body, cenv);
        for (var k in cenv.locals) cls[k] = cenv.locals[k];
        var ctor = function () {
          var inst = Object.create(cls);
          inst.__class__ = cls;
          if (typeof cls.__init__ === "function") {
            var a = [inst].concat([].slice.call(arguments));
            cls.__init__.apply(null, a);
          }
          return inst;
        };
        ctor.__dict__ = cls;
        env.set(node.name, ctor);
        return null;
      }
      case "Return": throw { type: "Return", value: node.value ? this.eval(node.value, env) : null };
      case "Break": throw { type: "Break" };
      case "Continue": throw { type: "Continue" };
      case "Pass": return null;
      case "Global":
        node.names.forEach(function (n) { env.globalsMark[n] = true; });
        return null;
      case "Assert":
        if (!truthy(this.eval(node.test, env))) throw new PyError("AssertionError", node.line);
        return null;
      case "Raise": throw new PyError(pyStr(this.eval(node.value, env)), node.line);
      case "Try":
        try { return this.execBlock(node.body, env); }
        catch (e) { return this.execBlock(node.handlers, env); }
      case "Import":
        env.set(node.name, this.loadModule(node.name, env, node.line));
        return null;
      case "ImportFrom": {
        var mod = this.loadModule(node.mod, env, node.line);
        node.names.forEach(function (n) {
          if (n === "*") {
            for (var k in mod) env.set(k, mod[k]);
          } else env.set(n, mod[n]);
        });
        return null;
      }
      default: throw new PyError("unknown stmt " + node.type, node.line);
    }
  };
  Interpreter.prototype.assign = function (target, value, env) {
    if (target.type === "Name") env.set(target.id, value);
    else if (target.type === "Subscript") {
      var obj = this.eval(target.value, env);
      var idx = this.eval(target.index, env);
      obj[idx] = value;
    } else if (target.type === "Attribute") {
      this.eval(target.value, env)[target.attr] = value;
    } else if (target.type === "Tuple" || target.type === "List") {
      for (var i = 0; i < target.elts.length; i++) this.assign(target.elts[i], value[i], env);
    } else throw new PyError("cannot assign");
  };
  Interpreter.prototype.eval = function (node, env) {
    if (!node) return null;
    switch (node.type) {
      case "Num": return node.value;
      case "Str": return node.value;
      case "Const": return node.value;
      case "Name": return env.get(node.id);
      case "List": return node.elts.map(function (e) { return this.eval(e, env); }, this);
      case "Tuple": return node.elts.map(function (e) { return this.eval(e, env); }, this);
      case "Dict": {
        var d = {};
        for (var i = 0; i < node.keys.length; i++) d[this.eval(node.keys[i], env)] = this.eval(node.values[i], env);
        return d;
      }
      case "BinOp": {
        var l = this.eval(node.left, env), r = this.eval(node.right, env);
        switch (node.op) {
          case "+": return (Array.isArray(l) && Array.isArray(r)) ? l.concat(r) : l + r;
          case "-": return l - r;
          case "*":
            if (typeof l === "string" && typeof r === "number") return l.repeat(r);
            if (typeof r === "string" && typeof l === "number") return r.repeat(l);
            if (Array.isArray(l) && typeof r === "number") {
              var o = []; for (var n = 0; n < r; n++) o = o.concat(l); return o;
            }
            return l * r;
          case "/": return l / r;
          case "//": return Math.floor(l / r);
          case "%": return l % r;
          case "**": return Math.pow(l, r);
        }
        break;
      }
      case "UnaryOp":
        if (node.op === "not") return !truthy(this.eval(node.operand, env));
        if (node.op === "-") return -this.eval(node.operand, env);
        return this.eval(node.operand, env);
      case "BoolOp":
        if (node.op === "and") {
          var a = this.eval(node.left, env);
          return truthy(a) ? this.eval(node.right, env) : a;
        }
        var b = this.eval(node.left, env);
        return truthy(b) ? b : this.eval(node.right, env);
      case "Compare": {
        var L = this.eval(node.left, env), R = this.eval(node.right, env);
        switch (node.op) {
          case "==": return L === R || (L == R);
          case "!=": return L !== R;
          case "<": return L < R;
          case ">": return L > R;
          case "<=": return L <= R;
          case ">=": return L >= R;
          case "in":
            if (typeof R === "string") return R.indexOf(L) >= 0;
            if (Array.isArray(R)) return R.indexOf(L) >= 0;
            return Object.prototype.hasOwnProperty.call(R, L);
          case "is": return L === R;
          case "is not": return L !== R;
        }
        break;
      }
      case "Call": {
        var fn = this.eval(node.func, env);
        if (typeof fn !== "function") {
          throw new PyError("object is not callable");
        }
        var args = node.args.map(function (a) { return this.eval(a, env); }, this);
        if (node.func.type === "Attribute") {
          var owner = this.eval(node.func.value, env);
          if (owner && typeof owner === "object") {
            var raw = owner[node.func.attr];
            if (typeof raw === "function" && raw.__name__) {
              args = [owner].concat(args);
              return raw.apply(null, args);
            }
          }
        }
        return fn.apply(null, args);
      }
      case "Attribute": {
        var obj = this.eval(node.value, env);
        if (obj == null) throw new PyError("NoneType has no attribute " + node.attr);
        var v = obj[node.attr];
        if (typeof v === "function") {
          var bound = v.bind(obj);
          bound.__name__ = node.attr;
          return bound;
        }
        return v;
      }
      case "Subscript": {
        var seq = this.eval(node.value, env);
        var idx = this.eval(node.index, env);
        if (idx < 0 && (Array.isArray(seq) || typeof seq === "string")) idx += seq.length;
        return seq[idx];
      }
      case "Slice": {
        var s = this.eval(node.value, env);
        var lo = node.lower ? this.eval(node.lower, env) : 0;
        var hi = node.upper ? this.eval(node.upper, env) : s.length;
        return s.slice(lo, hi);
      }
      case "Lambda": {
        var self = this;
        return function (x) {
          var f = new Frame(env);
          f.locals[node.arg] = x;
          return self.eval(node.body, f);
        };
      }
      default: throw new PyError("unknown expr " + node.type);
    }
  };

  var API = global.TroyPython = {
    keywords: Object.keys(KEYWORDS),
    builtins: ["print","input","len","str","int","float","list","dict","tuple","range","enumerate","zip","min","max","sum","abs","round","sorted","reversed","type","bool","pow","math","random","time","json","sys","os"],
    parse: function (src) {
      return new Parser(tokenize(src)).parse();
    },
    check: function (src) {
      try { this.parse(src); return []; }
      catch (e) { return [{ message: e.message || String(e), line: e.line || 1 }]; }
    },
    outline: function (src) {
      try {
        var ast = this.parse(src);
        var out = [];
        (function walk(nodes) {
          (nodes || []).forEach(function (n) {
            if (n.type === "FunctionDef") out.push({ kind: "fn", name: n.name, line: n.line });
            if (n.type === "ClassDef") out.push({ kind: "cls", name: n.name, line: n.line });
            if (n.body) walk(n.body);
            if (n.orelse) walk(n.orelse);
          });
        })(ast.body);
        return out;
      } catch (e) { return []; }
    },
    complete: function (src, prefix) {
      var names = this.keywords.concat(this.builtins);
      try {
        tokenize(src).forEach(function (t) {
          if (t.type === "NAME" && names.indexOf(t.value) < 0) names.push(t.value);
        });
      } catch (e) {}
      prefix = prefix || "";
      return names.filter(function (n) { return n.indexOf(prefix) === 0; }).slice(0, 12);
    },
    run: function (src, io) {
      var interp = new Interpreter(io);
      interp.run(src);
      return interp;
    },
    // Per-line highlight with a memo cache. Most edits touch one line, so the
    // other N-1 lines are served from the cache instead of re-tokenized.
    highlightLine: (function () {
      var cache = Object.create(null);
      var keys = [];
      return function (line) {
        var hit = cache[line];
        if (hit !== undefined) return hit;
        var html = API.highlight(line);
        cache[line] = html;
        keys.push(line);
        if (keys.length > 4000) { delete cache[keys.shift()]; }
        return html;
      };
    })(),
    highlight: function (src) {
      var html = "";
      var i = 0, n = src.length;
      function esc(s) {
        return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
      }
      while (i < n) {
        if (src[i] === "#") {
          var c = "";
          while (i < n && src[i] !== "\n") c += src[i++];
          html += '<span class="tok-cmt">' + esc(c) + "</span>";
          continue;
        }
        if (src[i] === '"' || src[i] === "'") {
          var q = src[i], s = src[i++];
          while (i < n && src[i] !== q) {
            if (src[i] === "\\") { s += src[i] + (src[i + 1] || ""); i += 2; }
            else s += src[i++];
          }
          if (i < n) s += src[i++];
          html += '<span class="tok-str">' + esc(s) + "</span>";
          continue;
        }
        if (/[0-9]/.test(src[i])) {
          var num = "";
          while (i < n && /[0-9._]/.test(src[i])) num += src[i++];
          html += '<span class="tok-num">' + esc(num) + "</span>";
          continue;
        }
        if (/[A-Za-z_]/.test(src[i])) {
          var id = "";
          while (i < n && /[A-Za-z0-9_]/.test(src[i])) id += src[i++];
          var cls = KEYWORDS[id] ? "tok-kw" : "tok-op";
          html += '<span class="' + cls + '">' + esc(id) + "</span>";
          continue;
        }
        html += esc(src[i++]);
      }
      return html || " ";
    }
  };
})(window);
