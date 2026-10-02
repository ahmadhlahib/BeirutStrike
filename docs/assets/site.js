// Shared by the English (index.html) and Arabic (ar/index.html) pages.

// The 20 ranks (see app/.../progression/Rank.kt and XpConfig.kt).
const ranks = [
  ["private", "جندي", "Private", 0],
  ["private_first_class", "جندي أول", "Private First Class", 500],
  ["corporal", "عريف", "Corporal", 1200],
  ["sergeant", "رقيب", "Sergeant", 2100],
  ["staff_sergeant", "رقيب أول", "Staff Sergeant", 3200],
  ["warrant_officer", "معاون", "Warrant Officer", 4500],
  ["chief_warrant_officer", "معاون أول", "Chief Warrant Officer", 6000],
  ["second_lieutenant", "ملازم", "Second Lieutenant", 7700],
  ["first_lieutenant", "ملازم أول", "First Lieutenant", 9600],
  ["captain", "نقيب", "Captain", 11700],
  ["major", "رائد", "Major", 14000],
  ["lieutenant_colonel", "مقدم", "Lieutenant Colonel", 16500],
  ["colonel", "عقيد", "Colonel", 19200],
  ["brigadier_general", "عميد", "Brigadier General", 22100],
  ["major_general", "لواء", "Major General", 25200],
  ["general", "عماد", "General", 28500],
  ["field_commander", "قائد ميداني", "Field Commander", 32000],
  ["special_forces_commander", "قائد القوات الخاصة", "Special Forces Commander", 36000],
  ["supreme_commander", "القائد الأعلى", "Supreme Commander", 40500],
  ["beirut_legend", "أسطورة بيروت", "Beirut Legend", 45500],
];

// The assets folder, wherever the page is (the Arabic page is one folder down).
const assets = document.currentScript.src.replace(/[^/]*$/, "");
const arabic = document.documentElement.lang === "ar";
const words = arabic
  ? { level: "المستوى", start: "نقطة البداية", badge: "شارة" }
  : { level: "LEVEL", start: "Start here", badge: "badge" };

const list = document.getElementById("rank-list");
ranks.forEach(([file, ar, en, xp], i) => {
  const el = document.createElement("div");
  el.className = "rank" + (i === ranks.length - 1 ? " top" : "");
  const need = xp ? xp.toLocaleString("en-US") + " XP" : words.start;
  el.innerHTML =
    `<img src="${assets}ranks/rank_${file}.svg" alt="${arabic ? words.badge + " " + ar : en + " " + words.badge}" width="72" height="72" loading="lazy">` +
    `<div class="lv">${words.level} ${i + 1}</div><div class="ar" lang="ar">${ar}</div>` +
    `<div class="en" lang="en">${en}</div><div class="need">${need}</div>`;
  list.appendChild(el);
});

// Phone menu.
const menu = document.getElementById("menu"), toggle = document.querySelector(".menu-button");
toggle.addEventListener("click", () => {
  const open = menu.classList.toggle("open");
  toggle.setAttribute("aria-expanded", open);
});
menu.addEventListener("click", e => {
  if (e.target.tagName === "A") { menu.classList.remove("open"); toggle.setAttribute("aria-expanded", false); }
});

// Screenshot lightbox.
const box = document.getElementById("lightbox"), boxImg = box.querySelector("img");
document.querySelectorAll(".gallery button").forEach(b => b.addEventListener("click", () => {
  const img = b.querySelector("img");
  boxImg.src = img.src; boxImg.alt = img.alt; box.classList.add("open");
}));
box.addEventListener("click", () => box.classList.remove("open"));
document.addEventListener("keydown", e => { if (e.key === "Escape") box.classList.remove("open"); });
