import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const html = fs.readFileSync(path.join(root, "docs", "index.html"), "utf8");
const gradle = fs.readFileSync(path.join(root, "app", "build.gradle.kts"), "utf8");

function check(condition, message) {
  if (!condition) throw new Error(message);
}

const version = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
check(version, "Could not read Android versionName.");
check(
  html.includes(`Android · Version ${version}`) && html.includes(`&quot;${version}&quot;`) ||
    html.includes(`Android · Version ${version}`) && html.includes(`"${version}"`),
  `Landing-page version does not match Android ${version}.`
);

check(
  html.includes("connect-src 'none'") && html.includes("default-src 'none'"),
  "Landing page must retain its restrictive Content Security Policy."
);
check(
  !/\b(?:fetch|XMLHttpRequest|WebSocket|EventSource|sendBeacon)\s*(?:\(|\.)/.test(html),
  "Landing page must not make API or telemetry requests."
);
check(
  !/<(?:script|img|iframe|audio|video|source)\b[^>]*\bsrc\s*=/i.test(html) &&
    !/<link\b[^>]*\brel\s*=\s*["']stylesheet["']/i.test(html) &&
    !/url\(\s*["']?https?:/i.test(html),
  "Landing page must remain self-contained without external assets."
);

const ids = new Set([...html.matchAll(/\bid\s*=\s*["']([^"']+)["']/gi)].map((match) => match[1]));
const hrefs = [...html.matchAll(/\bhref\s*=\s*["']([^"']+)["']/gi)].map((match) => match[1]);
for (const href of hrefs) {
  if (href.startsWith("#")) {
    check(ids.has(href.slice(1)), `Internal link ${href} has no matching id.`);
  } else if (href.startsWith("https://")) {
    check(
      href === "https://decode39.github.io/DailyCutReport/" ||
        href === "https://github.com/DeCode39/DailyCutReport" ||
        href === "https://github.com/DeCode39/DailyCutReport/releases",
      `Unexpected external link: ${href}`
    );
  } else {
    throw new Error(`Unexpected link target: ${href}`);
  }
}

for (const match of html.matchAll(/<a\b([^>]*)\bhref\s*=\s*["']https:\/\/github\.com[^"']*["']([^>]*)>/gi)) {
  const attributes = `${match[1]} ${match[2]}`;
  check(/\brel\s*=\s*["'][^"']*\bnoreferrer\b[^"']*["']/i.test(attributes), "External links must use noreferrer.");
}

check(fs.existsSync(path.join(root, "docs", ".nojekyll")), "docs/.nojekyll is required.");
console.log(`Landing page validated for DailyCutReport ${version}.`);
