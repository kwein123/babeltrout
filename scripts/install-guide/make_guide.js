// Builds the one-page Babeltrout install guide (US Letter) with docx-js.
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType,
  AlignmentType, BorderStyle, LevelFormat, ExternalHyperlink,
} = require("docx");

const ACCENT = "2A6F8F";
const MUTED = "55656F";
const FONT = "Calibri";
const SIZE = 18; // half-points: 9 pt body
const CONTENT = 12240 - 2 * 720; // 0.5" margins

const t = (text, opts = {}) => new TextRun({ text, font: FONT, size: SIZE, ...opts });
const b = (text, opts = {}) => t(text, { bold: true, ...opts });
const fa = (text) => new TextRun({ text, font: "Arial", size: SIZE + 2, rightToLeft: true });

const para = (children, opts = {}) => new Paragraph({ spacing: { after: 40, line: 252 }, children, ...opts });

const heading = (text, extra = []) => new Paragraph({
  spacing: { before: 110, after: 40 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: "C9D6DD", space: 1 } },
  children: [new TextRun({ text, font: FONT, size: 22, bold: true, color: ACCENT }), ...extra],
});

const step = (children) => new Paragraph({
  numbering: { reference: "steps", level: 0 }, spacing: { after: 30, line: 252 }, children,
});
let stepList = 0;
const steps = (items) => {
  const ref = `steps${stepList++}`;
  return items.map((children) => new Paragraph({
    numbering: { reference: ref, level: 0 }, spacing: { after: 30, line: 252 }, children,
  }));
};
const bullet = (children) => new Paragraph({
  numbering: { reference: "bullets", level: 0 }, spacing: { after: 30, line: 252 }, children,
});

const border = { style: BorderStyle.SINGLE, size: 4, color: "C9D6DD" };
const borders = { top: border, bottom: border, left: border, right: border };
const cell = (children, width, { header = false, shade } = {}) => new TableCell({
  borders, width: { size: width, type: WidthType.DXA },
  shading: header ? { fill: "E1EEF4", type: ShadingType.CLEAR, color: "auto" } : shade,
  margins: { top: 40, bottom: 40, left: 90, right: 90 },
  children: [new Paragraph({ spacing: { after: 0, line: 240 }, children })],
});
const table = (widths, rows) => new Table({
  width: { size: widths.reduce((a, c) => a + c, 0), type: WidthType.DXA },
  columnWidths: widths,
  rows: rows.map((r, i) => new TableRow({
    tableHeader: i === 0,
    children: r.map((c, j) => cell(c, widths[j], { header: i === 0 })),
  })),
});

// ---- Storage table ----
const storage = table([5650, 2431, 2431], [
  [[b("What gets downloaded")], [b("Download")], [b("Space on phone (about)")]],
  [[t("Babeltrout app (most phones: the arm64-v8a file)")], [t("47 MB")], [t("50 MB")]],
  [[t("Translation model, per language except English")], [t("~30 MB")], [t("30 MB")]],
  [[t("First Farsi or Hindi voice: Compact / Full")], [t("21 / 67 MB")], [t("35 / 80 MB "), t("(includes 18 MB shared once)", { color: MUTED })]],
  [[t("Each additional voice: Compact / Full")], [t("21 / 67 MB")], [t("17 / 63 MB")]],
  [[t("Google voice and offline speech pack, other languages (Android manages these)")], [t("varies")], [t("often tens of MB each; "), b("none exist for Farsi")]],
]);

// ---- Farsi voice table ----
const voices = table([1900, 8612], [
  [[b("Farsi voice")], [b("What it sounds like")]],
  [[b("Amir")], [t("Clear and neutral. The classic Farsi voice and the best first choice.")]],
  [[b("Gyro")], [t("Male voice; handles everyday text well.")]],
  [[b("Ganji")], [t("Natural voice from the Datacula recordings.")]],
  [[b("Ganji (literary)")], [t("Same family, formal literary reading style.")]],
  [[b("Reza Ibrahim")], [t("Recitation style; also reads English words.")]],
]);

const link = (url, label) => new ExternalHyperlink({
  link: url, children: [new TextRun({ text: label, font: FONT, size: SIZE, style: "Hyperlink", bold: true })],
});

const doc = new Document({
  creator: "Babeltrout",
  title: "Babeltrout: install and get started",
  styles: { default: { document: { run: { font: FONT, size: SIZE } } } },
  numbering: {
    config: [
      ...Array.from({ length: 6 }, (_, i) => ({
        reference: `steps${i}`,
        levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 300, hanging: 260 } }, run: { font: FONT, size: SIZE, bold: true, color: ACCENT } } }],
      })),
      { reference: "bullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 300, hanging: 220 } } } }] },
    ],
  },
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 }, margin: { top: 620, bottom: 560, left: 720, right: 720 } } },
    children: [
      new Paragraph({ spacing: { after: 0 }, children: [new TextRun({ text: "Babeltrout: install and get started", font: FONT, size: 34, bold: true, color: "17222B" })] }),
      para([t("Speak in one language and hear it in another. Free Android app, no account needed.", { color: MUTED })], { spacing: { after: 60 } }),

      heading("What you need"),
      bullet([b("An Android phone"), t(" with Android 8 or newer. Hands-free conversation needs Android 13 or newer. (No iPhone version yet.)")]),
      bullet([b("Wi-Fi"), t(" for the one-time downloads, and "), b("free space"), t(" from the table below. Plan on 1 GB free for all nine starting languages.")]),

      heading("1. Install the app (about 5 minutes)"),
      ...steps([
        [t("On the phone, open "), link("https://github.com/kwein123/babeltrout/releases/latest", "github.com/kwein123/babeltrout/releases/latest"), t(" in the web browser.")],
        [t("Under "), b("Assets"), t(", tap the file ending in "), b("arm64-v8a.apk"), t(" (about 47 MB). Only very old phones need the armeabi-v7a file instead.")],
        [t("Open the downloaded file. If Android asks, allow your browser to "), b("install unknown apps"), t(", then tap "), b("Install"), t(".")],
        [t("If Google Play Protect warns about an unknown app, tap "), b("More details → Install anyway"), t(". If Android says the developer isn't verified, ask the person who shared the app with you.")],
      ]),

      heading("2. First-time setup (on Wi-Fi)"),
      ...steps([
        [t("Open Babeltrout and "), b("allow the microphone"), t(".")],
        [b("Choose your languages first"), t(" to save space: tap "), b("✎ Edit"), t(" above the buttons, tap a language to remove it, or "), b("+ Add language"), t(" (25 available). Tap "), b("Done"), t(".")],
        [t("Tap "), b("Setup & diagnostics → Install Assets"), t(" and wait until the status box at the top stops saying “Installing…” (a few minutes). Tap the status box to read a long message.")],
      ]),

      heading("3. Farsi setup  ", [fa("فارسی")]),
      para([t("Google's voice engine has "), b("no Farsi voice"), t(", so Babeltrout brings its own. They work offline once downloaded. You need at least one:")]),
      ...steps([
        [t("Tap "), b("Setup & diagnostics → Voice Library: download Farsi & Hindi voices → Farsi"), t(".")],
        [t("Pick a voice, then "), b("Compact"), t(" (21 MB) or "), b("Full"), t(" (67 MB). Most listeners hear little difference, so start with Compact. It plays a sample when done.")],
        [t("To switch voices later: "), b("Choose Voices (e.g. Farsi) → Farsi"), t(". Remove unwanted voices in the Voice Library.")],
      ]),
      voices,
      para([b("Good to know: "), t("Farsi translation and Farsi speech work offline, but "), b("understanding spoken Farsi needs internet"), t(", because Android has no offline Farsi speech recognition. When the reply is in a language you can't read, Babeltrout shows how to say it in your own alphabet (Persian letters for a Farsi speaker). You don't need the separate SherpaTTS app.")], { spacing: { before: 50, after: 40, line: 252 } }),

      heading("Storage on your phone"),
      storage,
      para([b("Examples: "), t("English + Farsi with one Compact voice: about 115 MB. All nine starting languages + one Compact Farsi voice: about 325 MB, plus Android's voices and speech packs for the other languages.")], { spacing: { before: 50, after: 40, line: 252 } }),

      heading("4. Using it"),
      bullet([b("Translate:"), t(" pick the "), b("Target language"), t(", then hold the button for the language you're speaking. Start after the beep and let go when you finish.")]),
      bullet([b("Conversation:"), t(" tap "), b("Conversation"), t(", choose Language A and B, and tap "), b("Conversation Mic"), t(" (it turns red when on). Just talk; a short pause ends each turn.")]),
      bullet([b("Updates:"), t(" install a newer file from the same page over the old one (your settings stay). Or add "), b("github.com/kwein123/babeltrout"), t(" to the Obtainium app to be told about updates.")]),
    ],
  }],
});

Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(process.argv[2], buf);
  console.log("wrote", process.argv[2]);
});
