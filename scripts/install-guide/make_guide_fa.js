// Builds the one-page Farsi (right-to-left) Babeltrout install guide (US Letter) with docx-js.
// App button names stay in English, because the app's interface is English.
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType,
  AlignmentType, BorderStyle, LevelFormat, ExternalHyperlink,
} = require("docx");

const ACCENT = "2A6F8F";
const MUTED = "55656F";
const FONT = "Arial"; // has Persian glyphs on Windows, macOS and Android
const SIZE = 19;
const Z = "‌"; // zero-width non-joiner (نیم‌فاصله)

// Persian text runs are right-to-left; English UI names are ordinary left-to-right runs.
const fa = (text, opts = {}) => new TextRun({
  text: text.replace(/~/g, Z), font: FONT, size: SIZE, rightToLeft: true,
  language: { bidirectional: "fa-IR" }, ...opts,
});
const fab = (text, opts = {}) => fa(text, { bold: true, ...opts });
const en = (text, opts = {}) => new TextRun({ text, font: FONT, size: SIZE - 1, bold: true, ...opts });

const p = (children, opts = {}) => new Paragraph({
  bidirectional: true, alignment: AlignmentType.START, spacing: { after: 40, line: 264 }, children, ...opts,
});
const heading = (text) => new Paragraph({
  bidirectional: true, spacing: { before: 110, after: 40 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: "C9D6DD", space: 1 } },
  children: [fa(text, { size: 23, bold: true, color: ACCENT })],
});
let listNo = 0;
const steps = (items) => {
  const ref = `steps${listNo++}`;
  return items.map((children) => new Paragraph({
    bidirectional: true, numbering: { reference: ref, level: 0 }, spacing: { after: 30, line: 264 }, children,
  }));
};
const bullet = (children) => new Paragraph({
  bidirectional: true, numbering: { reference: "bullets", level: 0 }, spacing: { after: 30, line: 264 }, children,
});

const border = { style: BorderStyle.SINGLE, size: 4, color: "C9D6DD" };
const borders = { top: border, bottom: border, left: border, right: border };
const cell = (children, width, header) => new TableCell({
  borders, width: { size: width, type: WidthType.DXA },
  shading: header ? { fill: "E1EEF4", type: ShadingType.CLEAR, color: "auto" } : undefined,
  margins: { top: 40, bottom: 40, left: 90, right: 90 },
  children: [new Paragraph({ bidirectional: true, spacing: { after: 0, line: 250 }, children })],
});
const table = (widths, rows) => new Table({
  visuallyRightToLeft: true,
  width: { size: widths.reduce((a, c) => a + c, 0), type: WidthType.DXA },
  columnWidths: widths,
  rows: rows.map((r, i) => new TableRow({ tableHeader: i === 0, children: r.map((c, j) => cell(c, widths[j], i === 0)) })),
});

const link = (url, label) => new ExternalHyperlink({
  link: url, children: [new TextRun({ text: label, font: FONT, size: SIZE - 1, style: "Hyperlink", bold: true })],
});

const voices = table([2100, 8412], [
  [[fab("صدای فارسی")], [fab("ویژگی")]],
  [[en("Amir")], [fa("واضح و بی~طرف. صدای کلاسیک فارسی و بهترین انتخاب برای شروع.")]],
  [[en("Gyro")], [fa("صدای مردانه؛ متن~های روزمره را خوب می~خواند.")]],
  [[en("Ganji")], [fa("صدای طبیعی از ضبط~های "), en("Datacula", { bold: false }), fa(".")]],
  [[en("Ganji (literary)")], [fa("از همان خانواده، با خوانش ادبی و رسمی.")]],
  [[en("Reza Ibrahim")], [fa("سبک تلاوت؛ کلمات انگلیسی را هم می~خواند.")]],
]);

const storage = table([5450, 2300, 2762], [
  [[fab("چه چیزی دانلود می~شود")], [fab("حجم دانلود")], [fab("فضا روی گوشی (تقریبی)")]],
  [[fa("خود برنامه (برای بیشتر گوشی~ها فایل "), en("arm64-v8a", { bold: false }), fa(")")], [fa("۴۷ مگابایت")], [fa("۵۰ مگابایت")]],
  [[fa("مدل ترجمه، برای هر زبان به~جز انگلیسی")], [fa("حدود ۳۰ مگابایت")], [fa("۳۰ مگابایت")]],
  [[fa("اولین صدای فارسی یا هندی ("), en("Compact", { bold: false }), fa(" یا "), en("Full", { bold: false }), fa(")")],
    [fa("۲۱ یا ۶۷ مگابایت")], [fa("۳۵ یا ۸۰ مگابایت "), fa("(شامل ۱۸ مگابایت مشترک، فقط یک~بار)", { color: MUTED })]],
  [[fa("هر صدای اضافه ("), en("Compact", { bold: false }), fa(" یا "), en("Full", { bold: false }), fa(")")], [fa("۲۱ یا ۶۷ مگابایت")], [fa("۱۷ یا ۶۳ مگابایت")]],
  [[fa("صدا و بستهٔ گفتار آفلاین گوگل برای زبان~های دیگر (اندروید مدیریت می~کند)")], [fa("متغیر")],
    [fa("معمولاً چند ده مگابایت برای هر زبان؛ "), fab("برای فارسی وجود ندارد")]],
]);

const doc = new Document({
  creator: "Babeltrout",
  title: "راهنمای نصب Babeltrout",
  styles: { default: { document: { run: { font: FONT, size: SIZE, rightToLeft: true } } } },
  numbering: {
    config: [
      ...Array.from({ length: 6 }, (_, i) => ({
        reference: `steps${i}`,
        levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.START,
          style: { paragraph: { indent: { start: 320, hanging: 280 } }, run: { font: FONT, size: SIZE, bold: true, color: ACCENT } } }],
      })),
      { reference: "bullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.START,
          style: { paragraph: { indent: { start: 320, hanging: 240 } } } }] },
    ],
  },
  sections: [{
    properties: {
      bidi: true,
      page: { size: { width: 12240, height: 15840 }, margin: { top: 620, bottom: 560, left: 720, right: 720 } },
    },
    children: [
      p([fa("راهنمای نصب و راه~اندازی ", { size: 34, bold: true, color: "17222B" }), en("Babeltrout", { size: 34, color: "17222B" })], { spacing: { after: 0 } }),
      p([fa("به یک زبان حرف بزنید و ترجمه را به زبان دیگر بشنوید. برنامهٔ رایگان اندروید، بدون نیاز به حساب کاربری. نام دکمه~های برنامه انگلیسی است و در این راهنما به همان شکل آمده است.", { color: MUTED })], { spacing: { after: 60, line: 264 } }),

      heading("آنچه لازم دارید"),
      bullet([fab("یک گوشی اندروید"), fa(" با اندروید ۸ یا جدیدتر. برای مکالمهٔ بدون دست، اندروید ۱۳ یا جدیدتر لازم است. (هنوز نسخهٔ آیفون وجود ندارد.)")]),
      bullet([en("Wi-Fi"), fa(" برای دانلودهای یک~باره، و "), fab("فضای خالی"), fa(" مطابق جدول پایین. برای هر نُه زبان اولیه، حدود ۱ گیگابایت فضای خالی در نظر بگیرید.")]),

      heading("۱. نصب برنامه (حدود ۵ دقیقه)"),
      ...steps([
        [fa("در مرورگر گوشی این نشانی را باز کنید: "), link("https://github.com/kwein123/babeltrout/releases/latest", "github.com/kwein123/babeltrout/releases/latest")],
        [fa("در بخش "), en("Assets"), fa(" روی فایلی بزنید که نامش به "), en("arm64-v8a.apk"), fa(" ختم می~شود (حدود ۴۷ مگابایت). فقط گوشی~های خیلی قدیمی به فایل "), en("armeabi-v7a", { bold: false }), fa(" نیاز دارند.")],
        [fa("فایل دانلودشده را باز کنید. اگر اندروید پرسید، به مرورگر اجازهٔ "), fab("نصب برنامه~های ناشناس"), fa(" بدهید و سپس "), fab("نصب"), fa(" را بزنید.")],
        [fa("اگر "), en("Google Play Protect"), fa(" دربارهٔ برنامهٔ ناشناس هشدار داد، روی "), fab("جزئیات بیشتر"), fa(" و سپس "), fab("در هر صورت نصب شود"), fa(" بزنید. اگر اندروید گفت سازندهٔ برنامه تأیید نشده است، با کسی که برنامه را برایتان فرستاده تماس بگیرید.")],
      ]),

      heading("۲. راه~اندازی اولیه (با Wi-Fi)"),
      ...steps([
        [fa("برنامه را باز کنید و "), fab("اجازهٔ استفاده از میکروفون"), fa(" را بدهید.")],
        [fab("اول زبان~هایتان را انتخاب کنید"), fa(" تا در فضا صرفه~جویی شود: روی "), en("✎ Edit"), fa(" بالای دکمه~ها بزنید؛ برای حذف یک زبان روی آن بزنید، یا با "), en("+ Add language"), fa(" زبان اضافه کنید (۲۵ زبان موجود است). سپس "), en("Done"), fa(" را بزنید.")],
        [fa("روی "), en("Setup & diagnostics → Install Assets"), fa(" بزنید و صبر کنید تا کادر وضعیت بالای صفحه دیگر «"), en("Installing…", { bold: false }), fa("» نشان ندهد (چند دقیقه). برای خواندن پیام~های طولانی روی کادر وضعیت بزنید.")],
      ]),

      heading("۳. راه~اندازی فارسی"),
      p([fa("موتور صدای گوگل "), fab("صدای فارسی ندارد"), fa("، به همین دلیل برنامه صداهای فارسی خودش را دارد. این صداها پس از دانلود بدون اینترنت کار می~کنند. دست~کم یکی لازم است:")]),
      ...steps([
        [fa("روی "), en("Setup & diagnostics → Voice Library: download Farsi & Hindi voices → Farsi"), fa(" بزنید.")],
        [fa("یک صدا را انتخاب کنید و سپس "), en("Compact"), fa(" (۲۱ مگابایت) یا "), en("Full"), fa(" (۶۷ مگابایت) را بزنید. بیشتر شنوندگان تفاوت چندانی نمی~شنوند، پس با "), en("Compact", { bold: false }), fa(" شروع کنید. پس از نصب یک نمونه پخش می~شود.")],
        [fa("برای عوض کردن صدا در آینده: "), en("Choose Voices (e.g. Farsi) → Farsi"), fa(". صداهای اضافه را در "), en("Voice Library", { bold: false }), fa(" حذف کنید.")],
      ]),
      voices,
      p([fab("نکتهٔ مهم: "), fa("ترجمهٔ فارسی و صدای فارسی بدون اینترنت کار می~کنند، اما "), fab("تشخیص گفتار فارسی به اینترنت نیاز دارد"), fa("، چون اندروید تشخیص گفتار فارسیِ آفلاین ندارد. وقتی پاسخ به زبانی است که خواندنش را بلد نیستید، برنامه تلفظ آن را با حروف فارسی نشان می~دهد تا بتوانید آن را بلند بگویید. به برنامهٔ جداگانهٔ "), en("SherpaTTS", { bold: false }), fa(" نیازی ندارید.")], { spacing: { before: 50, after: 40, line: 264 } }),

      heading("فضای لازم روی گوشی"),
      storage,
      p([fab("مثال~ها: "), fa("انگلیسی و فارسی با یک صدای فشرده: حدود ۱۱۵ مگابایت. هر نُه زبان اولیه با یک صدای فارسی فشرده: حدود ۳۲۵ مگابایت، به~علاوهٔ صداها و بسته~های گفتار اندروید برای زبان~های دیگر.")], { spacing: { before: 50, after: 40, line: 264 } }),

      heading("۴. استفاده"),
      bullet([fab("ترجمه: "), en("Target language"), fa(" (زبان مقصد) را انتخاب کنید، سپس دکمهٔ زبانی را که با آن حرف می~زنید نگه دارید. بعد از بوق شروع کنید و وقتی حرفتان تمام شد، دکمه را رها کنید.")]),
      bullet([fab("مکالمه: "), fa("روی "), en("Conversation"), fa(" بزنید، "), en("Language A"), fa(" و "), en("Language B"), fa(" را انتخاب کنید و "), en("Conversation Mic"), fa(" را بزنید (وقتی روشن است قرمز می~شود). فقط صحبت کنید؛ یک مکث کوتاه، نوبت هر نفر را تمام می~کند.")]),
      bullet([fab("به~روزرسانی: "), fa("فایل جدیدتر را از همان صفحه دانلود کنید و روی نسخهٔ قبلی نصب کنید (تنظیمات شما می~ماند). یا نشانی "), en("github.com/kwein123/babeltrout"), fa(" را به برنامهٔ "), en("Obtainium", { bold: false }), fa(" اضافه کنید تا از نسخه~های جدید باخبر شوید.")]),
    ],
  }],
});

Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(process.argv[2], buf);
  console.log("wrote", process.argv[2]);
});
