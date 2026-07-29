const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

const SRC = path.resolve(__dirname, '..', 'app', 'inspiration', 'new_app_icon', 'app_icon.png');
const RES = path.resolve(__dirname, '..', 'app', 'src', 'main', 'res');

const mipmapSizes = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

async function main() {
  const srcBuffer = fs.readFileSync(SRC);
  console.log(`Source: ${SRC} (${srcBuffer.length} bytes)`);

  // 1. Foreground drawable for adaptive icon — use full res
  for (const dir of ['drawable-nodpi', 'drawable']) {
    const dest = path.join(RES, dir, 'ic_launcher_foreground.png');
    fs.writeFileSync(dest, srcBuffer);
    console.log(`Wrote ${dest}`);
  }

  // 2. Legacy mipmap launcher icons
  for (const [dir, size] of Object.entries(mipmapSizes)) {
    const destDir = path.join(RES, dir);
    if (!fs.existsSync(destDir)) fs.mkdirSync(destDir, { recursive: true });

    await sharp(srcBuffer)
      .resize(size, size)
      .png()
      .toFile(path.join(destDir, 'ic_launcher.png'));

    await sharp(srcBuffer)
      .resize(size, size)
      .png()
      .toFile(path.join(destDir, 'ic_launcher_round.png'));

    console.log(`Generated ${dir}/ic_launcher.png (${size}x${size})`);
  }

  console.log('Done!');
}

main().catch(err => { console.error(err); process.exit(1); });
