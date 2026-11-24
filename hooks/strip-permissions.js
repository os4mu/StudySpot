// after_prepare hook: removes permissions that plugins add but StudySpot does not need.
// Final manifest must match the permissions table in the README (least privilege).
//
// NOTE: do NOT include READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE / READ_MEDIA_IMAGES
// here. cordova-plugin-camera needs them to hand a captured photo back from the native
// Camera app into the app sandbox (FILE_URI flow) - stripping them makes "Take photo"
// fail with "Could not read the photo" even though the permission prompt/camera UI
// appears to work fine. This file previously had those in REMOVE, copied over from an
// older project (StopAlarm) that had no camera feature; that was the real bug.
const fs = require('fs');
const path = require('path');

const REMOVE = [
  'android.permission.READ_MEDIA_VIDEO',
  'android.permission.READ_MEDIA_AUDIO',
  'android.permission.READ_MEDIA_VISUAL_USER_SELECTED',
  'android.permission.RECORD_AUDIO',
  'android.permission.MODIFY_AUDIO_SETTINGS',
  'android.permission.ACCESS_BACKGROUND_LOCATION',
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.INTERNET',
];

module.exports = function (ctx) {
  const manifest = path.join(ctx.opts.projectRoot, 'platforms/android/app/src/main/AndroidManifest.xml');
  if (!fs.existsSync(manifest)) return;
  let xml = fs.readFileSync(manifest, 'utf8');
  REMOVE.forEach((perm) => {
    const re = new RegExp('\\s*<uses-permission[^>]*android:name="' + perm.replace(/\./g, '\\.') + '"[^>]*/>', 'g');
    xml = xml.replace(re, '');
  });
  fs.writeFileSync(manifest, xml);
  console.log('[strip-permissions] cleaned AndroidManifest.xml');
};
