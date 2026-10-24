#!/usr/bin/env node
/*
 * Copies Jaago's native alarm code into the Android project and adds the
 * permissions and components it needs to AndroidManifest.xml.
 * Run after `npx cap add android` (safe to run again any time).
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const androidMain = path.join(root, 'android', 'app', 'src', 'main');
const manifestPath = path.join(androidMain, 'AndroidManifest.xml');
const javaDir = path.join(androidMain, 'java', 'com', 'jaago', 'app');
const srcDir = path.join(root, 'native', 'android', 'com', 'jaago', 'app');

if (!fs.existsSync(manifestPath)) {
  console.error('Android project not found. Run "npx cap add android" first.');
  process.exit(1);
}

// 1. Java files
fs.mkdirSync(javaDir, { recursive: true });
for (const f of fs.readdirSync(srcDir)) {
  if (!f.endsWith('.java')) continue;
  fs.copyFileSync(path.join(srcDir, f), path.join(javaDir, f));
  console.log('copied  ' + f);
}

// 2. Manifest
let xml = fs.readFileSync(manifestPath, 'utf8');
const MARK = '<!-- jaago:';

if (!xml.includes(MARK + 'permissions')) {
  const perms = `
    ${MARK}permissions -->
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>
`;
  xml = xml.replace(/(\s*)<application/, perms + '$1<application');
  console.log('added   permissions');
}

if (!xml.includes(MARK + 'components')) {
  const comps = `
        ${MARK}components -->
        <receiver android:name=".AlarmReceiver" android:exported="false" />
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
        <service
            android:name=".AlarmService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    `;
  xml = xml.replace(/<\/application>/, comps + '</application>');
  console.log('added   receivers and service');
}

fs.writeFileSync(manifestPath, xml);
console.log('\nDone. Next: npx cap sync android && npx cap open android');
