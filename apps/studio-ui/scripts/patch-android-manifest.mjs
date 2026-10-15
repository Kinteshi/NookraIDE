import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const manifestPath = resolve(
  scriptDirectory,
  "../src-tauri/gen/android/app/src/main/AndroidManifest.xml",
);
const networkSecurityConfigPath = resolve(
  scriptDirectory,
  "../src-tauri/gen/android/app/src/main/res/xml/network_security_config.xml",
);

let manifest;
try {
  manifest = await readFile(manifestPath, "utf8");
} catch (cause) {
  throw new Error(
    `Android manifest not found at ${manifestPath}; run the android:init script first.`,
    { cause },
  );
}

const mainActivity = /<activity\b(?=[^>]*\bandroid:name="[^"]*MainActivity")[^>]*>/s;
const match = manifest.match(mainActivity);
if (!match) {
  throw new Error(`Could not find the Tauri MainActivity in ${manifestPath}.`);
}

const resizeableAttribute = /\bandroid:resizeableActivity="[^"]*"/;
const updatedActivity = resizeableAttribute.test(match[0])
  ? match[0].replace(resizeableAttribute, 'android:resizeableActivity="true"')
  : match[0].replace(/>$/, ' android:resizeableActivity="true">');
if (updatedActivity !== match[0]) {
  manifest = manifest.replace(match[0], updatedActivity);
}

const application = /<application\b[^>]*>/s;
const applicationMatch = manifest.match(application);
if (!applicationMatch) {
  throw new Error(`Could not find the application element in ${manifestPath}.`);
}
const networkSecurityAttribute = /\bandroid:networkSecurityConfig="[^"]*"/;
const updatedApplication = networkSecurityAttribute.test(applicationMatch[0])
  ? applicationMatch[0].replace(
      networkSecurityAttribute,
      'android:networkSecurityConfig="@xml/network_security_config"',
    )
  : applicationMatch[0].replace(
      />$/,
      ' android:networkSecurityConfig="@xml/network_security_config">',
    );
if (updatedApplication !== applicationMatch[0]) {
  manifest = manifest.replace(applicationMatch[0], updatedApplication);
}

const networkSecurityConfig = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
`;
let currentNetworkSecurityConfig;
try {
  currentNetworkSecurityConfig = await readFile(networkSecurityConfigPath, "utf8");
} catch (cause) {
  if (
    !(
      cause &&
      typeof cause === "object" &&
      "code" in cause &&
      cause.code === "ENOENT"
    )
  ) {
    throw cause;
  }
  currentNetworkSecurityConfig = null;
}
if (manifest !== match.input) await writeFile(manifestPath, manifest);
if (currentNetworkSecurityConfig !== networkSecurityConfig) {
  await writeFile(networkSecurityConfigPath, networkSecurityConfig);
}
