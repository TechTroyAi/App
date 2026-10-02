/* ============================================================================
 * NUMSTRIKE // Mental Math Status & Secret Technique Engine
 * Built for Grade 12 / High-Speed Arithmetic & Multiplication Mastery
 * ============================================================================ */

(function () {
  "use strict";

  const STORAGE_KEY = "numstrike_profile_v1";

  const RANKS = [
    { rank: "F", level: 1, title: "Calculator Bound", minCp: 0 },
    { rank: "E", level: 2, title: "Number Initiate", minCp: 250 },
    { rank: "D", level: 3, title: "Pattern Breaker", minCp: 600 },
    { rank: "C", level: 4, title: "Reflex Striker", minCp: 1200 },
    { rank: "B", level: 5, title: "Left-to-Right Slicer", minCp: 2200 },
    { rank: "A", level: 6, title: "Human Abacus", minCp: 3800 },
    { rank: "S", level: 7, title: "Lightning Mathematician", minCp: 6000 },
  ];

  const SECRET_GUIDES = [
    {
      id: "base10_gap",
      badge: "SECRET #1 • FOR 6, 7, 8, 9",
      title: "The Base-10 Ghost Gap (Vedic Method)",
      desc: "When your mind blanks on 7×8, 6×9, or 8×8, never guess. Look at how far each number is from 10. Cross-subtract for the first digit, and multiply the tiny gaps for the second digit!",
      example: "Example: 8 × 7\n• Distance from 10: 8 needs [2], 7 needs [3]\n• First digit (cross-subtract): 8 − 3 = 5 (or 7 − 2 = 5)\n• Second digit (multiply gaps): 2 × 3 = 6\n• Snap together → 56!",
      drillMode: "secret_base10"
    },
    {
      id: "five_anchor",
      badge: "SECRET #2 • ANTI-FREEZE FALLBACK",
      title: "The 5-Anchor Jump (×6, ×7, ×8)",
      desc: "Your brain already knows ×5 effortlessly (half of ×10). Use 5 as a springboard so you never get stuck on ×6 or ×7.",
      example: "Example: 6 × 7\n• Anchor at 5: 5 × 7 = 35\n• Add one more 7: 35 + 7 = 42!\n\nExample: 7 × 8\n• Anchor at 5: (5 × 8) + (2 × 8) = 40 + 16 = 56!",
      drillMode: "secret_anchor5"
    },
    {
      id: "double_engine",
      badge: "SECRET #3 • ZERO MEMORIZATION",
      title: "The Double-Double Engine (×4, ×8, ×12)",
      desc: "Multiplying by 4 is just doubling twice. Multiplying by 8 is doubling three times. Multiplying by 12 is (×10) + (×2).",
      example: "Example: 4 × 7 → Double 7 (14) → Double again (28)!\nExample: 8 × 6 → 6 → 12 → 24 → 48!\nExample: 12 × 7 → (10 × 7) + (2 × 7) = 70 + 14 = 84!",
      drillMode: "secret_double"
    },
    {
      id: "nine_snap",
      badge: "SECRET #4 • INSTANT 9s PATTERN",
      title: "The 9s Complement Snap (10 − 1 Rule)",
      desc: "For 9 × N (from 2 to 9): the first digit is ALWAYS (N − 1), and the two digits ALWAYS add up to 9!",
      example: "Example: 9 × 7\n• First digit: 7 − 1 = 6\n• What adds to 6 to make 9? 3!\n• Answer → 63! (Or think: 70 − 7 = 63)",
      drillMode: "secret_nines"
    },
    {
      id: "left_to_right",
      badge: "SECRET #5 • ARTHUR BENJAMIN'S #1 RULE",
      title: "Left-to-Right Slicing (2-Digit × 1-Digit)",
      desc: "School taught you to multiply right-to-left and 'carry', which overloads working memory. Always slice the big tens part FIRST from left to right!",
      example: "Example: 17 × 6\n• Slice 17 into 10 + 7\n• Big part first: 10 × 6 = 60\n• Small part next: 7 × 6 = 42\n• Add left-to-right: 60 + 42 = 102!",
      drillMode: "slicer_2d"
    },
    {
      id: "halve_double",
      badge: "SECRET #6 • NUMBER RESHAPING",
      title: "Halve & Double Reshaper",
      desc: "When multiplying an even number by 5, 15, or 4, cut the even number in half and double the other number to turn it into a 1-second calculation.",
      example: "Example: 16 × 5 → Halve 16 (8), Double 5 (10) → 8 × 10 = 80!\nExample: 14 × 4 → Halve 14 (7), Double 4 (8) → 7 × 8 = 56!",
      drillMode: "secret_halvedouble"
    }
  ];

  // Default persistent state
  function createDefaultProfile() {
    const matrix = {};
    // Pre-seed realistic initial state so the 12x12 matrix & weakness radar are immediately clear
    for (let a = 2; a <= 12; a++) {
      for (let b = 2; b <= 12; b++) {
        const key = `${Math.min(a, b)}x${Math.max(a, b)}`;
        if (!matrix[key]) {
          matrix[key] = { hits: 0, misses: 0, bestMs: null, lastMs: null };
        }
      }
    }
    return {
      cp: 0,
      streak: 1,
      bestCombo: 0,
      totalSolved: 0,
      totalWrong: 0,
      totalTimeMs: 0,
      soundOn: true,
      inputMode: "choices", // "choices" | "numpad"
      penaltyMode: "standard", // "standard" (-20 CP) | "brutal" (-40 CP & -3s)
      selectedMode: "boss_times",
      matrix: matrix
    };
  }

  let profile = loadProfile();

  function loadProfile() {
    try {
      let raw = localStorage.getItem(STORAGE_KEY);
      if (!raw && window.JadexNative && typeof window.JadexNative.readFile === "function") {
        raw = window.JadexNative.readFile(STORAGE_KEY + ".json");
      }
      if (raw) {
        const parsed = JSON.parse(raw);
        const def = createDefaultProfile();
        return Object.assign(def, parsed, {
          matrix: Object.assign(def.matrix, parsed.matrix || {})
        });
      }
    } catch (e) {
      console.warn("Failed to load profile:", e);
    }
    return createDefaultProfile();
  }

  function saveProfile() {
    try {
      const serialized = JSON.stringify(profile);
      localStorage.setItem(STORAGE_KEY, serialized);
      if (window.JadexNative && typeof window.JadexNative.saveFile === "function") {
        window.JadexNative.saveFile(STORAGE_KEY + ".json", serialized);
      }
    } catch (e) {
      console.warn("Failed to save profile:", e);
    }
  }

  // ==========================================================================
  // WEB AUDIO SYNTHESIZER + ANDROID NATIVE HAPTICS
  // ==========================================================================
  let audioCtx = null;

  function ensureAudio() {
    if (!audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) {
        audioCtx = new AudioContextClass();
      }
    }
    if (audioCtx && audioCtx.state === "suspended") {
      audioCtx.resume().catch(() => {});
    }
  }

  function triggerHaptic() {
    try {
      if (window.JadexNative && typeof window.JadexNative.haptic === "function") {
        window.JadexNative.haptic();
      } else if (navigator.vibrate) {
        navigator.vibrate(18);
      }
    } catch (e) {}
  }

  function playSfx(type, comboLevel = 1) {
    triggerHaptic();
    if (!profile.soundOn) return;
    ensureAudio();
    if (!audioCtx) return;

    const now = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.connect(gain);
    gain.connect(audioCtx.destination);

    if (type === "correct") {
      const baseFreq = 440 + Math.min(comboLevel, 8) * 55;
      osc.type = "triangle";
      osc.frequency.setValueAtTime(baseFreq, now);
      osc.frequency.exponentialRampToValueAtTime(baseFreq * 1.35, now + 0.11);
      gain.gain.setValueAtTime(0.16, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.13);
      osc.start(now);
      osc.stop(now + 0.13);
    } else if (type === "wrong") {
      osc.type = "sawtooth";
      osc.frequency.setValueAtTime(165, now);
      osc.frequency.linearRampToValueAtTime(95, now + 0.24);
      gain.gain.setValueAtTime(0.22, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.26);
      osc.start(now);
      osc.stop(now + 0.26);
    } else if (type === "tap") {
      osc.type = "sine";
      osc.frequency.setValueAtTime(620, now);
      gain.gain.setValueAtTime(0.06, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.04);
      osc.start(now);
      osc.stop(now + 0.04);
    } else if (type === "rankup") {
      [523.25, 659.25, 783.99, 1046.5].forEach((freq, idx) => {
        const o = audioCtx.createOscillator();
        const g = audioCtx.createGain();
        o.type = "triangle";
        o.frequency.setValueAtTime(freq, now + idx * 0.07);
        g.gain.setValueAtTime(0.15, now + idx * 0.07);
        g.gain.exponentialRampToValueAtTime(0.001, now + idx * 0.07 + 0.22);
        o.connect(g);
        g.connect(audioCtx.destination);
        o.start(now + idx * 0.07);
        o.stop(now + idx * 0.07 + 0.22);
      });
    }
  }

  // ==========================================================================
  // SECRET TECHNIQUE GENERATOR (EXPLAINS ANY BASIC MATH QUESTION)
  // ==========================================================================
  function explainSecret(a, b, op) {
    if (op === "×") {
      const min = Math.min(a, b);
      const max = Math.max(a, b);

      // 1. 9s Complement Snap
      if (min === 9 && max <= 10) {
        const other = a === 9 ? b : a;
        const tens = other - 1;
        const ones = 9 - tens;
        return {
          chip: "⚡ SECRET: 9s SNAP (10 − 1)",
          whisper: `<strong>9s Snap:</strong> First digit is (${other} − 1) = <strong>${tens}</strong>. Digits of ×9 always sum to 9 (${tens} + <strong>${ones}</strong>)!`,
          full: `<strong>9s Complement Trick:</strong> (${other} × 10) − ${other} = ${other * 10} − ${other} = <strong>${a * b}</strong>. Or: starts with ${tens}, and ${tens}+${ones}=9 → <strong>${a * b}</strong>!`
        };
      }

      // 2. 2-Digit Left-to-Right Slicer (e.g., 14 x 6, 17 x 7)
      if (max >= 12 && min >= 2 && min <= 9) {
        const tensPart = Math.floor(max / 10) * 10;
        const onesPart = max % 10;
        const p1 = tensPart * min;
        const p2 = onesPart * min;
        // Also check if Halve & Double is super clean (e.g., even x 5)
        if (min === 5 && max % 2 === 0) {
          return {
            chip: "⚡ SECRET: HALVE & DOUBLE",
            whisper: `<strong>Halve &amp; Double:</strong> Cut ${max} in half (<strong>${max / 2}</strong>) and multiply by 10!`,
            full: `<strong>Halve &amp; Double:</strong> ${max} × 5 is the same as (${max} ÷ 2) × 10 = <strong>${max / 2} × 10 = ${a * b}</strong>!`
          };
        }
        return {
          chip: "⚡ SECRET: LEFT-TO-RIGHT SLICER",
          whisper: `<strong>Slice ${max} → ${tensPart} + ${onesPart}:</strong> Do (${tensPart}×${min} = <strong>${p1}</strong>) + (${onesPart}×${min} = <strong>${p2}</strong>)`,
          full: `<strong>Left-to-Right Slicing:</strong> Never carry right-to-left! Slice ${max} into ${tensPart}+${onesPart}: (${tensPart}×${min}) + (${onesPart}×${min}) = <strong>${p1} + ${p2} = ${a * b}</strong>!`
        };
      }

      // 3. Base-10 Ghost Gap for 6, 7, 8, 9 x 6, 7, 8, 9
      if (a >= 6 && a <= 9 && b >= 6 && b <= 9) {
        const gapA = 10 - a;
        const gapB = 10 - b;
        const cross = a - gapB; // equals b - gapA
        const tail = gapA * gapB;
        const anchor5 = max * 5;
        const rem = min - 5;
        const remProd = max * rem;
        return {
          chip: "⚡ SECRET: BASE-10 GAP / 5-ANCHOR",
          whisper: `<strong>5-Anchor:</strong> (${max}×5) + (${max}×${rem}) = <strong>${anchor5} + ${remProd}</strong> &nbsp;|&nbsp; <strong>Base-10 Gap:</strong> (${a}−${gapB})=<strong>${cross}</strong>0 + (${gapA}×${gapB})=<strong>${tail}</strong>`,
          full: `<strong>2 Ways to Never Blank on ${a}×${b}:</strong><br>1) <strong>Base-10 Gap:</strong> ${a} needs ${gapA}, ${b} needs ${gapB}. Cross-subtract (${a}−${gapB}=${cross} → ${cross * 10}) + multiply gaps (${gapA}×${gapB}=${tail}) = <strong>${a * b}</strong>.<br>2) <strong>5-Anchor:</strong> (${max}×5 = ${anchor5}) + (${max}×${rem} = ${remProd}) = <strong>${a * b}</strong>!`
        };
      }

      // 4. Double-Double for x4 or x8
      if (min === 4) {
        const d1 = max * 2;
        return {
          chip: "⚡ SECRET: DOUBLE-DOUBLE (×4)",
          whisper: `<strong>Double-Double:</strong> Double ${max} → <strong>${d1}</strong>, then double ${d1} again!`,
          full: `<strong>×4 Double-Double:</strong> Double ${max} to get ${d1}, then double ${d1} to get <strong>${a * b}</strong>!`
        };
      }
      if (min === 8) {
        const d1 = max * 2;
        const d2 = d1 * 2;
        return {
          chip: "⚡ SECRET: TRIPLE-DOUBLE (×8)",
          whisper: `<strong>Double 3x:</strong> ${max} → ${d1} → <strong>${d2}</strong> → double one more time!`,
          full: `<strong>×8 Triple-Double:</strong> ${max} → ${d1} → ${d2} → <strong>${a * b}</strong>! (Or 10×${max} − 2×${max} = ${max * 10} − ${max * 2} = <strong>${a * b}</strong>)`
        };
      }

      // 5. x6 Anchor
      if (min === 6) {
        const fives = max * 5;
        return {
          chip: "⚡ SECRET: 5-ANCHOR + 1",
          whisper: `<strong>5-Anchor:</strong> (${max} × 5 = <strong>${fives}</strong>) + ${max}`,
          full: `<strong>×6 Shortcut:</strong> Start at ×5 (${fives}) and add one more ${max}: ${fives} + ${max} = <strong>${a * b}</strong>!`
        };
      }

      // Default multiplication breakdown
      const anchor = max * 5;
      const diff = min - 5;
      return {
        chip: "⚡ SECRET: CHUNK & SNAP",
        whisper: diff > 0
          ? `<strong>Anchor at 5:</strong> (${max}×5 = <strong>${anchor}</strong>) + (${max}×${diff} = <strong>${max * diff}</strong>)`
          : `<strong>Double &amp; Add:</strong> Break ${min}×${max} into easy doubles`,
        full: `<strong>Fast Breakdown:</strong> ${a} × ${b} = <strong>${a * b}</strong>.`
      };
    }

    if (op === "÷") {
      // a ÷ b = ans  (where a = b * ans)
      const ans = a / b;
      return {
        chip: "⚡ SECRET: REVERSE FACTOR SNAP",
        whisper: `<strong>Flip to Times:</strong> Think <strong>${b} × ? = ${a}</strong> (${b} × 5 = ${b * 5}, ${b} × 10 = ${b * 10})`,
        full: `<strong>Reverse Multiplication:</strong> ${a} ÷ ${b} is asking "${b} times what equals ${a}?" Since ${b} × ${ans} = ${a}, the answer is <strong>${ans}</strong>!`
      };
    }

    if (op === "+") {
      const tensB = Math.floor(b / 10) * 10;
      const onesB = b % 10;
      return {
        chip: "⚡ SECRET: LEFT-TO-RIGHT JUMP",
        whisper: `<strong>Add Tens First:</strong> (${a} + ${tensB} = <strong>${a + tensB}</strong>), then + ${onesB}`,
        full: `<strong>Left-to-Right Addition:</strong> Add the tens first (${a} + ${tensB} = ${a + tensB}), then add ${onesB} = <strong>${a + b}</strong>!`
      };
    }

    if (op === "−") {
      const roundUp = Math.ceil(b / 10) * 10;
      const extra = roundUp - b;
      return {
        chip: "⚡ SECRET: COMPLEMENT ADD-BACK",
        whisper: `<strong>Subtract Round ${roundUp} first:</strong> (${a} − ${roundUp} = <strong>${a - roundUp}</strong>), then add back <strong>${extra}</strong>!`,
        full: `<strong>Complement Subtraction:</strong> Subtract ${roundUp} (${a} − ${roundUp} = ${a - roundUp}), then add back the ${extra} you over-subtracted = <strong>${a - b}</strong>!`
      };
    }

    return {
      chip: "⚡ MENTAL STRIKE",
      whisper: "Visualize from left to right",
      full: `Correct answer: <strong>${a}</strong>`
    };
  }

  // ==========================================================================
  // QUESTION GENERATOR BY MODE
  // ==========================================================================
  function randInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
  }

  function pickRandom(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
  }

  let lastQuestionSig = "";

  function generateQuestion(mode) {
    let a, b, op, answer, matrixKey = null;

    for (let attempt = 0; attempt < 12; attempt++) {
      if (mode === "boss_times") {
        // Focus heavily on the tough 6, 7, 8, 9, 12 tables
        const bosses = [
          [6, 6], [6, 7], [6, 8], [6, 9], [6, 12],
          [7, 7], [7, 8], [7, 9], [7, 12],
          [8, 8], [8, 9], [8, 12],
          [9, 9], [9, 12], [12, 12],
          [4, 7], [4, 8], [4, 9], [3, 8], [3, 7]
        ];
        const pair = pickRandom(bosses);
        if (Math.random() < 0.5) {
          a = pair[0]; b = pair[1];
        } else {
          a = pair[1]; b = pair[0];
        }
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "slicer_2d") {
        a = randInt(12, 29);
        b = randInt(3, 9);
        op = "×";
        answer = a * b;
      } else if (mode === "weakness_assassin") {
        const weakPairs = getWeakestPairs(10);
        if (weakPairs.length > 0 && Math.random() < 0.85) {
          const picked = pickRandom(weakPairs);
          const parts = picked.key.split("x").map(Number);
          if (Math.random() < 0.5) {
            a = parts[0]; b = parts[1];
          } else {
            a = parts[1]; b = parts[0];
          }
        } else {
          a = randInt(6, 12);
          b = randInt(6, 9);
        }
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "mixed_blitz") {
        const roll = Math.random();
        if (roll < 0.5) {
          // Multiplication
          a = randInt(4, 12);
          b = randInt(6, 12);
          op = "×";
          answer = a * b;
          matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
        } else if (roll < 0.72) {
          // Division based on times tables
          b = randInt(4, 12);
          answer = randInt(4, 12);
          a = b * answer;
          op = "÷";
          matrixKey = `${Math.min(b, answer)}x${Math.max(b, answer)}`;
        } else if (roll < 0.86) {
          a = randInt(24, 78);
          b = randInt(15, 59);
          op = "+";
          answer = a + b;
        } else {
          a = randInt(42, 96);
          b = randInt(16, a - 11);
          op = "−";
          answer = a - b;
        }
      } else if (mode === "secret_base10") {
        a = randInt(6, 9);
        b = randInt(6, 9);
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "secret_anchor5") {
        a = pickRandom([6, 7, 8]);
        b = randInt(6, 9);
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "secret_double") {
        a = pickRandom([4, 8, 12]);
        b = randInt(6, 12);
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "secret_nines") {
        a = 9;
        b = randInt(3, 12);
        if (Math.random() < 0.5) { const t = a; a = b; b = t; }
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      } else if (mode === "secret_halvedouble") {
        a = pickRandom([12, 14, 16, 18, 22, 24, 26]);
        b = pickRandom([4, 5, 6]);
        op = "×";
        answer = a * b;
      } else {
        a = randInt(6, 9);
        b = randInt(6, 9);
        op = "×";
        answer = a * b;
        matrixKey = `${Math.min(a, b)}x${Math.max(a, b)}`;
      }

      const sig = `${a}${op}${b}`;
      if (sig !== lastQuestionSig) {
        lastQuestionSig = sig;
        break;
      }
    }

    const secret = explainSecret(a, b, op);
    const choices = buildSmartChoices(a, b, op, answer);

    return {
      a,
      b,
      op,
      answer,
      matrixKey,
      secret,
      choices
    };
  }

  function buildSmartChoices(a, b, op, answer) {
    const set = new Set([answer]);
    const candidates = [];

    if (op === "×") {
      // Smart traps: adjacent multiples & common table confusion (e.g. 54 vs 56 vs 48 vs 64)
      candidates.push(
        answer + a,
        answer - a,
        answer + b,
        answer - b,
        (a - 1) * (b + 1),
        (a + 1) * (b - 1),
        answer + 10,
        answer - 10,
        answer + 2,
        answer - 2
      );
      // Reverse digits if 2-digit
      if (answer >= 12 && answer <= 98) {
        const rev = Number(String(answer).split("").reverse().join(""));
        if (rev > 10 && rev !== answer) candidates.push(rev);
      }
    } else {
      candidates.push(
        answer + 1,
        answer - 1,
        answer + 2,
        answer - 2,
        answer + 10,
        answer - 10,
        answer + 4,
        answer - 4
      );
    }

    for (const c of candidates) {
      if (set.size >= 4) break;
      if (c > 0 && c !== answer) {
        set.add(c);
      }
    }

    while (set.size < 4) {
      const fallback = answer + randInt(-9, 9);
      if (fallback > 0 && fallback !== answer) set.add(fallback);
    }

    // Shuffle
    const arr = Array.from(set);
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const tmp = arr[i];
      arr[i] = arr[j];
      arr[j] = tmp;
    }
    return arr;
  }

  // ==========================================================================
  // RANK & WEAKNESS CALCULATIONS
  // ==========================================================================
  function getRankInfo(cp) {
    let current = RANKS[0];
    let next = RANKS[1];
    for (let i = 0; i < RANKS.length; i++) {
      if (cp >= RANKS[i].minCp) {
        current = RANKS[i];
        next = RANKS[i + 1] || null;
      }
    }
    let pct = 100;
    if (next) {
      const span = next.minCp - current.minCp;
      pct = Math.max(0, Math.min(100, Math.round(((cp - current.minCp) / span) * 100)));
    }
    return { current, next, pct };
  }

  function getWeakestPairs(limit = 8) {
    const entries = [];
    for (const [key, stat] of Object.entries(profile.matrix)) {
      const [a, b] = key.split("x").map(Number);
      // Prioritize 6, 7, 8, 9, 12 if unplayed, or pairs with misses / slow times
      let troubleScore = 0;
      if (stat.misses > 0) troubleScore += stat.misses * 50;
      if (stat.lastMs && stat.lastMs > 2500) troubleScore += (stat.lastMs - 2000) / 50;
      if (stat.hits === 0 && a >= 6 && b >= 6 && a <= 9 && b <= 9) {
        troubleScore += 35; // Default high-priority boss tables
      }
      if (troubleScore > 0) {
        entries.push({ key, a, b, stat, troubleScore });
      }
    }
    entries.sort((x, y) => y.troubleScore - x.troubleScore);
    return entries.slice(0, limit);
  }

  function countBossMastery() {
    let mastered = 0;
    let total = 0;
    for (let a = 6; a <= 12; a++) {
      for (let b = a; b <= 12; b++) {
        if (a <= 9 || a === 12) {
          total++;
          const key = `${a}x${b}`;
          const st = profile.matrix[key];
          if (st && st.hits > 0 && st.bestMs !== null && st.bestMs <= 2200 && st.misses === 0) {
            mastered++;
          }
        }
      }
    }
    return { mastered, total };
  }

  // ==========================================================================
  // UI RENDERING (HOME, SECRETS, STATUS)
  // ==========================================================================
  function renderTopAndHome() {
    const { current, next, pct } = getRankInfo(profile.cp);

    document.getElementById("top-rank-letter").textContent = current.rank;
    document.getElementById("top-rank-title").textContent = `RANK ${current.rank} • ${current.title.toUpperCase()}`;
    document.getElementById("top-xp-fill").style.width = `${pct}%`;
    document.getElementById("top-cp-val").textContent = profile.cp;
    document.getElementById("top-streak-val").textContent = profile.streak;
    document.getElementById("btn-sound-toggle").textContent = profile.soundOn ? "🔊" : "🔇";

    document.getElementById("hero-level-num").textContent = current.level;
    document.getElementById("hero-rank-name").textContent = `Rank ${current.rank}: ${current.title}`;
    document.getElementById("hero-rank-badge").textContent = current.rank;

    if (next) {
      const needed = next.minCp - profile.cp;
      document.getElementById("hero-next-rank-hint").textContent =
        `Need +${needed} CP to ascend to Rank ${next.rank}: ${next.title}`;
      document.getElementById("hero-xp-text").textContent = `${profile.cp} / ${next.minCp} CP`;
    } else {
      document.getElementById("hero-next-rank-hint").textContent =
        "MAX RANK ACHIEVED — Sovereign Mental Calculator!";
      document.getElementById("hero-xp-text").textContent = `${profile.cp} CP (MAX)`;
    }
    document.getElementById("hero-xp-bar").style.width = `${pct}%`;

    // Attributes
    const avgSpd = profile.totalSolved > 0
      ? (profile.totalTimeMs / profile.totalSolved / 1000).toFixed(1) + "s"
      : "--";
    const totalAttempts = profile.totalSolved + profile.totalWrong;
    const acc = totalAttempts > 0
      ? Math.round((profile.totalSolved / totalAttempts) * 100) + "%"
      : "100%";
    const boss = countBossMastery();

    document.getElementById("stat-spd").textContent = avgSpd;
    document.getElementById("stat-acc").textContent = acc;
    document.getElementById("stat-best-combo").textContent = `${profile.bestCombo}x`;
    document.getElementById("stat-boss-mastery").textContent = `${boss.mastered}/${boss.total}`;

    // Input & Penalty mode toggles
    document.querySelectorAll("#input-mode-toggle .seg-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.input === profile.inputMode);
    });
    document.querySelectorAll("#penalty-mode-toggle .seg-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.penalty === profile.penaltyMode);
    });

    // Mode cards
    document.querySelectorAll("#modes-grid .mode-card").forEach((card) => {
      card.classList.toggle("selected", card.dataset.mode === profile.selectedMode);
    });

    const weakCount = getWeakestPairs(12).length;
    document.getElementById("weakness-count-badge").textContent =
      weakCount > 0 ? `${weakCount} TARGETS` : "ADAPTIVE";
  }

  // Interactive Secret Breakdown Lab (Screen 3)
  let labA = 7;
  let labB = 8;

  function renderSecretLab() {
    document.querySelectorAll("#lab-num-a .lab-pill").forEach((b) => {
      b.classList.toggle("active", Number(b.dataset.val) === labA);
    });
    document.querySelectorAll("#lab-num-b .lab-pill").forEach((b) => {
      b.classList.toggle("active", Number(b.dataset.val) === labB);
    });

    const sec = explainSecret(labA, labB, "×");
    const panel = document.getElementById("lab-result-panel");
    panel.innerHTML = `
      <div class="lab-eq-headline">${labA} × ${labB} = ${labA * labB}</div>
      <div class="lab-step">${sec.full}</div>
    `;
  }

  function renderSecretGuides() {
    const container = document.getElementById("secrets-list");
    container.innerHTML = SECRET_GUIDES.map((g) => `
      <article class="secret-card">
        <div class="secret-card-header">
          <span class="secret-badge">${g.badge}</span>
        </div>
        <h3>${g.title}</h3>
        <p>${g.desc}</p>
        <pre class="secret-example-box">${g.example}</pre>
        <button class="btn-drill-secret" data-drill="${g.drillMode}">
          ⚡ Drill This Secret Now (45s)
        </button>
      </article>
    `).join("");

    container.querySelectorAll(".btn-drill-secret").forEach((btn) => {
      btn.addEventListener("click", () => {
        playSfx("tap");
        startArenaSession(btn.dataset.drill, 45);
      });
    });
  }

  function renderStatusAndMatrix() {
    const { current } = getRankInfo(profile.cp);
    const ladderEl = document.getElementById("rank-ladder-card");
    ladderEl.innerHTML = `
      <div class="panel-header-row">
        <h3>🏆 Hunter Status Rank Ladder</h3>
        <span style="font-size:12px;color:var(--cyan);font-family:var(--font-mono);">Current: ${profile.cp} CP</span>
      </div>
      <div class="rank-ladder-list">
        ${RANKS.slice().reverse().map((r) => `
          <div class="ladder-row ${r.rank === current.rank ? "current-rank" : ""}">
            <span><strong>Rank ${r.rank}</strong> — ${r.title}</span>
            <span style="font-family:var(--font-mono);">${r.minCp} CP</span>
          </div>
        `).join("")}
      </div>
    `;

    // Weakness chips
    const weakList = getWeakestPairs(8);
    const weakEl = document.getElementById("weakness-chips-container");
    if (weakList.length === 0) {
      weakEl.innerHTML = `<span style="font-size:12px;color:var(--text-muted);">No weaknesses flagged yet! Play a blitz session to scan your reaction speeds.</span>`;
    } else {
      weakEl.innerHTML = weakList.map((w) => {
        const label = w.stat.misses > 0
          ? `${w.a}×${w.b} (${w.stat.misses} miss)`
          : w.stat.lastMs
            ? `${w.a}×${w.b} (${(w.stat.lastMs / 1000).toFixed(1)}s)`
            : `${w.a}×${w.b} (Boss)`;
        return `<button class="weak-chip" data-a="${w.a}" data-b="${w.b}">${label}</button>`;
      }).join("");

      weakEl.querySelectorAll(".weak-chip").forEach((chip) => {
        chip.addEventListener("click", () => {
          const a = Number(chip.dataset.a);
          const b = Number(chip.dataset.b);
          inspectMatrixCell(a, b);
        });
      });
    }

    // 12x12 Multiplication Matrix
    const gridEl = document.getElementById("mastery-matrix-grid");
    let html = `<div class="m-cell m-hdr">×</div>`;
    for (let col = 1; col <= 12; col++) {
      html += `<div class="m-cell m-hdr">${col}</div>`;
    }
    for (let row = 1; row <= 12; row++) {
      html += `<div class="m-cell m-hdr">${row}</div>`;
      for (let col = 1; col <= 12; col++) {
        let cls = "";
        if (row === 1 || col === 1 || row === 10 || col === 10) {
          cls = "m-cyan";
        } else {
          const key = `${Math.min(row, col)}x${Math.max(row, col)}`;
          const st = profile.matrix[key];
          if (st) {
            if (st.misses > 0 && (!st.bestMs || st.bestMs > 2200)) {
              cls = "m-red";
            } else if (st.hits > 0 && st.bestMs !== null && st.bestMs <= 2100) {
              cls = "m-gold";
            } else if (st.hits > 0) {
              cls = "m-cyan";
            }
          }
        }
        html += `<div class="m-cell ${cls}" data-r="${row}" data-c="${col}">${row * col}</div>`;
      }
    }
    gridEl.innerHTML = html;

    gridEl.querySelectorAll(".m-cell[data-r]").forEach((cell) => {
      cell.addEventListener("click", () => {
        const r = Number(cell.dataset.r);
        const c = Number(cell.dataset.c);
        inspectMatrixCell(r, c);
      });
    });
  }

  function inspectMatrixCell(a, b) {
    playSfx("tap");
    const key = `${Math.min(a, b)}x${Math.max(a, b)}`;
    const st = profile.matrix[key];
    const sec = explainSecret(a, b, "×");
    const bar = document.getElementById("matrix-inspect-bar");
    const speedInfo = st && st.bestMs
      ? `Best Speed: <strong>${(st.bestMs / 1000).toFixed(2)}s</strong> (${st.hits} hits, ${st.misses} misses)`
      : `Not drilled yet`;
    bar.innerHTML = `
      <div style="display:flex;justify-content:space-between;margin-bottom:4px;">
        <strong style="color:var(--gold);font-family:var(--font-mono);font-size:14px;">${a} × ${b} = ${a * b}</strong>
        <span style="font-size:11px;color:var(--cyan);">${speedInfo}</span>
      </div>
      <div>${sec.full}</div>
    `;
  }

  // ==========================================================================
  // LIVE ARENA GAME LOOP (WITH SCORE PENALTY, COMBO & AUTO-WHISPER)
  // ==========================================================================
  let arenaState = {
    active: false,
    mode: "boss_times",
    durationSec: 60,
    remainingSec: 60,
    sessionCp: 0,
    combo: 1,
    streakHits: 0,
    maxCombo: 1,
    solvedCount: 0,
    mistakeCount: 0,
    penaltyLostCp: 0,
    totalAnswerMs: 0,
    missedQuestions: [],
    currentQ: null,
    questionStartMs: 0,
    typedAnswer: "",
    whisperTimer: null,
    tickInterval: null,
    locked: false
  };

  const MODE_TITLES = {
    boss_times: "TIMES TABLE BOSSES (6,7,8,9,12)",
    slicer_2d: "2-DIGIT × 1-DIGIT SLICER",
    weakness_assassin: "WEAKNESS ASSASSIN",
    mixed_blitz: "GRADE 12 SPEED BLITZ",
    secret_base10: "SECRET DRILL: BASE-10 GAP",
    secret_anchor5: "SECRET DRILL: 5-ANCHOR",
    secret_double: "SECRET DRILL: DOUBLE ENGINE",
    secret_nines: "SECRET DRILL: 9s SNAP",
    secret_halvedouble: "SECRET DRILL: HALVE & DOUBLE"
  };

  function switchScreen(screenId) {
    document.querySelectorAll(".screen").forEach((s) => {
      s.classList.toggle("active", s.id === screenId);
    });
    document.querySelectorAll(".bottom-nav .nav-tab").forEach((t) => {
      t.classList.toggle("active", t.dataset.target === screenId);
    });
    // Hide bottom nav during active arena play for maximum focus
    const bottomNav = document.querySelector(".bottom-nav");
    if (screenId === "screen-arena") {
      bottomNav.classList.add("hidden");
    } else {
      bottomNav.classList.remove("hidden");
    }
  }

  function startArenaSession(mode = profile.selectedMode, durationSec = 60) {
    clearArenaTimers();
    arenaState = {
      active: true,
      mode: mode,
      durationSec: durationSec,
      remainingSec: durationSec,
      sessionCp: 0,
      combo: 1,
      streakHits: 0,
      maxCombo: 1,
      solvedCount: 0,
      mistakeCount: 0,
      penaltyLostCp: 0,
      totalAnswerMs: 0,
      missedQuestions: [],
      currentQ: null,
      questionStartMs: performance.now(),
      typedAnswer: "",
      whisperTimer: null,
      tickInterval: null,
      locked: false
    };

    if (window.JadexNative && typeof window.JadexNative.keepScreen === "function") {
      window.JadexNative.keepScreen(true);
    }

    document.getElementById("results-modal").classList.add("hidden");
    document.getElementById("penalty-toast").classList.add("hidden");
    document.getElementById("arena-mode-title").textContent = MODE_TITLES[mode] || "ARENA BLITZ";

    // Configure input mode UI
    const isNumpad = profile.inputMode === "numpad";
    document.getElementById("arena-choices-grid").classList.toggle("hidden", isNumpad);
    document.getElementById("arena-numpad-grid").classList.toggle("hidden", !isNumpad);
    document.getElementById("numpad-answer-box").classList.toggle("hidden", !isNumpad);

    switchScreen("screen-arena");
    updateArenaHud();
    nextArenaQuestion();

    let lastTick = performance.now();
    arenaState.tickInterval = setInterval(() => {
      if (!arenaState.active) return;
      const now = performance.now();
      const dt = (now - lastTick) / 1000;
      lastTick = now;

      arenaState.remainingSec = Math.max(0, arenaState.remainingSec - dt);
      const qElapsed = (now - arenaState.questionStartMs) / 1000;
      document.getElementById("arena-qtime-val").textContent = `${qElapsed.toFixed(1)}s`;

      const timerText = document.getElementById("arena-timer-text");
      const timerBar = document.getElementById("arena-timer-bar");
      timerText.textContent = `${arenaState.remainingSec.toFixed(1)}s`;
      const pct = (arenaState.remainingSec / arenaState.durationSec) * 100;
      timerBar.style.width = `${ Math.max(0, Math.min(100, pct)) }%`;
      timerBar.classList.toggle("low-time", arenaState.remainingSec <= 10);

      if (arenaState.remainingSec <= 0) {
        finishArenaSession();
      }
    }, 80);
  }

  function clearArenaTimers() {
    if (arenaState.whisperTimer) {
      clearTimeout(arenaState.whisperTimer);
      arenaState.whisperTimer = null;
    }
    if (arenaState.tickInterval) {
      clearInterval(arenaState.tickInterval);
      arenaState.tickInterval = null;
    }
  }

  function updateArenaHud() {
    document.getElementById("arena-score-val").textContent = arenaState.sessionCp;
    document.getElementById("arena-combo-text").textContent =
      arenaState.combo >= 5 ? "🔥 5x OVERDRIVE" : `${arenaState.combo}x COMBO`;

    const badgeWrap = document.getElementById("combo-badge-wrap");
    badgeWrap.classList.toggle("overdrive", arenaState.combo >= 5);

    const pips = document.querySelectorAll("#arena-combo-pips .pip");
    pips.forEach((pip, idx) => {
      pip.classList.toggle("lit", idx < arenaState.combo);
    });
  }

  function showScoreDelta(delta) {
    const el = document.getElementById("score-delta-popup");
    el.className = "score-delta-popup";
    void el.offsetWidth; // trigger reflow
    if (delta >= 0) {
      el.textContent = `+${delta} CP`;
      el.classList.add("show-gain");
    } else {
      el.textContent = `${delta} CP`;
      el.classList.add("show-loss");
    }
  }

  function nextArenaQuestion() {
    if (!arenaState.active) return;
    arenaState.locked = false;
    arenaState.typedAnswer = "";
    document.getElementById("numpad-typed-val").textContent = "?";

    const q = generateQuestion(arenaState.mode);
    arenaState.currentQ = q;
    arenaState.questionStartMs = performance.now();

    document.getElementById("arena-equation").textContent = `${q.a} ${q.op} ${q.b}`;
    document.getElementById("arena-trick-chip").textContent = q.secret.chip;

    // Reset Whisper Box (dims until 2.5s hesitation or user taps Peek)
    const whisperBox = document.getElementById("arena-whisper-box");
    whisperBox.classList.remove("active-whisper");
    document.getElementById("whisper-badge").textContent = "🧠 SECRET WHISPER (Auto-activates if you hesitate)";
    document.getElementById("whisper-body").innerHTML = q.secret.whisper;

    if (arenaState.whisperTimer) clearTimeout(arenaState.whisperTimer);
    arenaState.whisperTimer = setTimeout(() => {
      if (arenaState.active && !arenaState.locked) {
        whisperBox.classList.add("active-whisper");
        document.getElementById("whisper-badge").textContent = "⚡ MIND-FREEZE ASSIST: USE THIS SHORTCUT!";
      }
    }, 2400);

    // Populate 4 option buttons
    const btns = document.querySelectorAll("#arena-choices-grid .choice-btn");
    btns.forEach((btn, idx) => {
      btn.className = "choice-btn";
      btn.textContent = q.choices[idx];
      btn.dataset.val = q.choices[idx];
    });
  }

  function submitAnswer(userVal, clickedBtn = null) {
    if (!arenaState.active || arenaState.locked) return;
    const q = arenaState.currentQ;
    if (!q) return;

    const elapsedMs = performance.now() - arenaState.questionStartMs;
    const isCorrect = Number(userVal) === q.answer;
    const stage = document.getElementById("question-stage");

    if (isCorrect) {
      arenaState.locked = true;
      playSfx("correct", arenaState.combo);
      document.getElementById("penalty-toast").classList.add("hidden");

      if (clickedBtn) clickedBtn.classList.add("choice-correct");
      stage.classList.remove("flash-wrong");
      stage.classList.add("flash-correct");
      setTimeout(() => stage.classList.remove("flash-correct"), 220);

      // Speed bonus calculation
      let basePoints = 12;
      if (elapsedMs <= 1600) {
        basePoints = 20; // Lightning bonus
        arenaState.remainingSec = Math.min(arenaState.durationSec, arenaState.remainingSec + 0.8);
      } else if (elapsedMs <= 2800) {
        basePoints = 15;
        arenaState.remainingSec = Math.min(arenaState.durationSec, arenaState.remainingSec + 0.4);
      }

      const gainedCp = basePoints * arenaState.combo;
      arenaState.sessionCp += gainedCp;
      profile.cp += gainedCp;

      arenaState.streakHits++;
      arenaState.combo = Math.min(5, 1 + Math.floor(arenaState.streakHits / 2));
      if (arenaState.combo > arenaState.maxCombo) arenaState.maxCombo = arenaState.combo;
      if (arenaState.combo > profile.bestCombo) profile.bestCombo = arenaState.combo;

      arenaState.solvedCount++;
      arenaState.totalAnswerMs += elapsedMs;
      profile.totalSolved++;
      profile.totalTimeMs += elapsedMs;

      // Update 12x12 matrix
      if (q.matrixKey && profile.matrix[q.matrixKey]) {
        const m = profile.matrix[q.matrixKey];
        m.hits++;
        m.lastMs = Math.round(elapsedMs);
        if (m.bestMs === null || elapsedMs < m.bestMs) {
          m.bestMs = Math.round(elapsedMs);
        }
      }

      showScoreDelta(gainedCp);
      updateArenaHud();
      renderTopAndHome();
      saveProfile();

      setTimeout(() => {
        nextArenaQuestion();
      }, 160);
    } else {
      // WRONG ANSWER -> MINUS SCORE PENALTY!
      playSfx("wrong");
      if (clickedBtn) clickedBtn.classList.add("choice-wrong");

      const penaltyCp = profile.penaltyMode === "brutal" ? 40 : 20;
      arenaState.sessionCp -= penaltyCp;
      profile.cp = Math.max(0, profile.cp - penaltyCp);
      arenaState.penaltyLostCp += penaltyCp;
      arenaState.mistakeCount++;
      profile.totalWrong++;

      if (profile.penaltyMode === "brutal") {
        arenaState.remainingSec = Math.max(0, arenaState.remainingSec - 3.0);
      }

      // Shatter combo
      arenaState.streakHits = 0;
      arenaState.combo = 1;

      // Record in matrix as a miss
      if (q.matrixKey && profile.matrix[q.matrixKey]) {
        profile.matrix[q.matrixKey].misses++;
      }

      // Record missed question for post-game review
      if (!arenaState.missedQuestions.some((item) => item.eq === `${q.a} ${q.op} ${q.b}`)) {
        arenaState.missedQuestions.push({
          eq: `${q.a} ${q.op} ${q.b} = ${q.answer}`,
          explain: q.secret.full
        });
      }

      stage.classList.remove("flash-correct");
      stage.classList.add("flash-wrong");
      setTimeout(() => stage.classList.remove("flash-wrong"), 360);

      // Show Penalty Toast with Secret Breakdown!
      const toast = document.getElementById("penalty-toast");
      document.getElementById("penalty-badge-text").textContent =
        profile.penaltyMode === "brutal"
          ? `💀 WRONG! -${penaltyCp} CP & -3s`
          : `⚠️ WRONG CHOICE! -${penaltyCp} CP`;
      document.getElementById("penalty-correct-reveal").textContent =
        `${q.a} ${q.op} ${q.b} = ${q.answer}`;
      document.getElementById("penalty-trick-explain").innerHTML = q.secret.full;
      toast.classList.remove("hidden");

      // Also light up the whisper box so the user sees the trick clearly
      document.getElementById("arena-whisper-box").classList.add("active-whisper");

      showScoreDelta(-penaltyCp);
      updateArenaHud();
      renderTopAndHome();
      saveProfile();

      if (profile.inputMode === "numpad") {
        arenaState.typedAnswer = "";
        document.getElementById("numpad-typed-val").textContent = "?";
      }
    }
  }

  function finishArenaSession() {
    arenaState.active = false;
    clearArenaTimers();
    if (window.JadexNative && typeof window.JadexNative.keepScreen === "function") {
      window.JadexNative.keepScreen(false);
    }

    profile.streak++;
    saveProfile();
    renderTopAndHome();
    renderStatusAndMatrix();
    playSfx("rankup");

    const { current } = getRankInfo(profile.cp);
    const netSign = arenaState.sessionCp >= 0 ? "+" : "";
    const netEl = document.getElementById("res-net-cp");
    netEl.textContent = `${netSign}${arenaState.sessionCp} CP`;
    netEl.style.color = arenaState.sessionCp >= 0 ? "var(--emerald)" : "var(--crimson)";

    document.getElementById("res-rank-status").textContent =
      `Current Status: Rank ${current.rank} — ${current.title} (${profile.cp} CP)`;
    document.getElementById("res-solved").textContent = arenaState.solvedCount;
    document.getElementById("res-mistakes").textContent =
      `${arenaState.mistakeCount} (-${arenaState.penaltyLostCp} CP)`;

    const avgSec = arenaState.solvedCount > 0
      ? (arenaState.totalAnswerMs / arenaState.solvedCount / 1000).toFixed(2) + "s"
      : "--";
    document.getElementById("res-speed").textContent = avgSec;
    document.getElementById("res-combo").textContent = `${arenaState.maxCombo}x`;

    const missedListEl = document.getElementById("res-missed-list");
    if (arenaState.missedQuestions.length === 0) {
      missedListEl.innerHTML = `<div class="res-missed-item" style="color:var(--emerald);">Flawless session! Zero wrong-answer penalties taken.</div>`;
    } else {
      missedListEl.innerHTML = arenaState.missedQuestions.map((m) => `
        <div class="res-missed-item">
          <strong style="color:var(--gold);font-family:var(--font-mono);">${m.eq}</strong><br/>
          <span>${m.explain}</span>
        </div>
      `).join("");
    }

    document.getElementById("results-modal").classList.remove("hidden");
  }

  // ==========================================================================
  // EVENT LISTENERS & INITIALIZATION
  // ==========================================================================
  function bindEvents() {
    // Bottom Navigation
    document.querySelectorAll(".bottom-nav .nav-tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        playSfx("tap");
        const target = tab.dataset.target;
        if (target === "screen-status") renderStatusAndMatrix();
        switchScreen(target);
      });
    });

    // Header Status Trigger -> Jump to Status screen
    document.getElementById("header-status-trigger").addEventListener("click", () => {
      if (arenaState.active) return;
      playSfx("tap");
      renderStatusAndMatrix();
      switchScreen("screen-status");
    });

    // Sound Toggle
    document.getElementById("btn-sound-toggle").addEventListener("click", () => {
      profile.soundOn = !profile.soundOn;
      saveProfile();
      renderTopAndHome();
      if (profile.soundOn) playSfx("tap");
    });

    // Input Mode Toggle
    document.querySelectorAll("#input-mode-toggle .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        profile.inputMode = btn.dataset.input;
        saveProfile();
        renderTopAndHome();
        playSfx("tap");
      });
    });

    // Penalty Mode Toggle
    document.querySelectorAll("#penalty-mode-toggle .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        profile.penaltyMode = btn.dataset.penalty;
        saveProfile();
        renderTopAndHome();
        playSfx("tap");
      });
    });

    // Mode Selection Cards
    document.querySelectorAll("#modes-grid .mode-card").forEach((card) => {
      card.addEventListener("click", () => {
        profile.selectedMode = card.dataset.mode;
        saveProfile();
        renderTopAndHome();
        playSfx("tap");
      });
    });

    // Start Blitz Button
    document.getElementById("btn-start-blitz").addEventListener("click", () => {
      playSfx("tap");
      startArenaSession(profile.selectedMode, 60);
    });

    // Quit Arena
    document.getElementById("btn-quit-arena").addEventListener("click", () => {
      arenaState.active = false;
      clearArenaTimers();
      playSfx("tap");
      switchScreen("screen-home");
      renderTopAndHome();
    });

    // Peek Secret Button inside Arena
    document.getElementById("btn-peek-secret").addEventListener("click", () => {
      playSfx("tap");
      const wb = document.getElementById("arena-whisper-box");
      wb.classList.add("active-whisper");
      document.getElementById("whisper-badge").textContent = "💡 SECRET TECHNIQUE BREAKDOWN:";
    });

    // 4-Choice Buttons
    document.querySelectorAll("#arena-choices-grid .choice-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        submitAnswer(Number(btn.dataset.val), btn);
      });
    });

    // Pro Numpad Buttons
    document.querySelectorAll("#arena-numpad-grid .num-key").forEach((keyBtn) => {
      keyBtn.addEventListener("click", () => {
        if (!arenaState.active || arenaState.locked) return;
        const k = keyBtn.dataset.key;
        if (k === "C") {
          arenaState.typedAnswer = "";
          document.getElementById("numpad-typed-val").textContent = "?";
          playSfx("tap");
        } else if (k === "OK") {
          if (arenaState.typedAnswer.length > 0) {
            submitAnswer(Number(arenaState.typedAnswer));
          }
        } else {
          if (arenaState.typedAnswer.length < 4) {
            arenaState.typedAnswer += k;
            document.getElementById("numpad-typed-val").textContent = arenaState.typedAnswer;
            // Auto-submit if exact match!
            if (arenaState.currentQ && Number(arenaState.typedAnswer) === arenaState.currentQ.answer) {
              submitAnswer(Number(arenaState.typedAnswer));
            } else {
              playSfx("tap");
            }
          }
        }
      });
    });

    // Keyboard support for desktop/web preview testing
    window.addEventListener("keydown", (e) => {
      if (!arenaState.active || arenaState.locked) return;
      if (profile.inputMode === "choices") {
        if (["1", "2", "3", "4"].includes(e.key)) {
          const idx = Number(e.key) - 1;
          const btn = document.querySelectorAll("#arena-choices-grid .choice-btn")[idx];
          if (btn) submitAnswer(Number(btn.dataset.val), btn);
        }
      } else {
        if (e.key >= "0" && e.key <= "9") {
          if (arenaState.typedAnswer.length < 4) {
            arenaState.typedAnswer += e.key;
            document.getElementById("numpad-typed-val").textContent = arenaState.typedAnswer;
            if (arenaState.currentQ && Number(arenaState.typedAnswer) === arenaState.currentQ.answer) {
              submitAnswer(Number(arenaState.typedAnswer));
            }
          }
        } else if (e.key === "Backspace" || e.key === "Escape") {
          arenaState.typedAnswer = "";
          document.getElementById("numpad-typed-val").textContent = "?";
        } else if (e.key === "Enter" && arenaState.typedAnswer.length > 0) {
          submitAnswer(Number(arenaState.typedAnswer));
        }
      }
    });

    // Interactive Lab Number Pills (Screen 3)
    document.querySelectorAll("#lab-num-a .lab-pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        labA = Number(pill.dataset.val);
        playSfx("tap");
        renderSecretLab();
      });
    });
    document.querySelectorAll("#lab-num-b .lab-pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        labB = Number(pill.dataset.val);
        playSfx("tap");
        renderSecretLab();
      });
    });

    // Drill Weaknesses button (Screen 4)
    document.getElementById("btn-drill-weaknesses").addEventListener("click", () => {
      playSfx("tap");
      startArenaSession("weakness_assassin", 60);
    });

    // Reset Stats button
    document.getElementById("btn-reset-stats").addEventListener("click", () => {
      if (confirm("Reset your CP, Rank, and 12×12 Matrix stats back to Rank F?")) {
        profile = createDefaultProfile();
        saveProfile();
        renderTopAndHome();
        renderStatusAndMatrix();
      }
    });

    // Modal Buttons
    document.getElementById("btn-res-home").addEventListener("click", () => {
      playSfx("tap");
      document.getElementById("results-modal").classList.add("hidden");
      switchScreen("screen-home");
      renderTopAndHome();
    });

    document.getElementById("btn-res-again").addEventListener("click", () => {
      playSfx("tap");
      document.getElementById("results-modal").classList.add("hidden");
      startArenaSession(arenaState.mode, arenaState.durationSec);
    });
  }

  // Init
  bindEvents();
  renderTopAndHome();
  renderSecretLab();
  renderSecretGuides();
  renderStatusAndMatrix();
})();
