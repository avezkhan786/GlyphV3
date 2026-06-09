import sharp from 'sharp';
import { promises as fs } from 'fs';
import path from 'path';

// Define the icon sizes for each density
const sizes = {
  'mdpi': 48,
  'hdpi': 72,
  'xhdpi': 96,
  'xxhdpi': 144,
  'xxxhdpi': 192
};

const sourcePath = 'app/inspiration/logo.png';
const baseOutputDir = 'app/src/main/res';
const iconScale = 0.72;

console.log(`Converting ${sourcePath} to PNG icons with safe padding...`);
console.log();

async function convertIcons() {
  for (const [density, size] of Object.entries(sizes)) {
    const outputDir = path.join(baseOutputDir, `mipmap-${density}`);
    
    // Create output directory if it doesn't exist
    await fs.mkdir(outputDir, { recursive: true });
    
    const outputFile = path.join(outputDir, 'ic_launcher.png');
    const outputFileRound = path.join(outputDir, 'ic_launcher_round.png');
    const innerSize = Math.round(size * iconScale);
    
    try {
      const logoBuffer = await sharp(sourcePath)
        .resize(innerSize, innerSize, {
          fit: 'contain',
          background: { r: 255, g: 255, b: 255, alpha: 0 }
        })
        .png()
        .toBuffer();

      // Create padded square launcher icon (white background + centered logo)
      await sharp({
        create: {
          width: size,
          height: size,
          channels: 4,
          background: { r: 255, g: 255, b: 255, alpha: 1 }
        }
      })
        .composite([{ input: logoBuffer, gravity: 'center' }])
        .png()
        .toFile(outputFile);
      
      console.log(`✓ Created ${outputFile} (${size}x${size})`);
      
      // Create round icon asset using same padded source
      await sharp({
        create: {
          width: size,
          height: size,
          channels: 4,
          background: { r: 255, g: 255, b: 255, alpha: 1 }
        }
      })
        .composite([{ input: logoBuffer, gravity: 'center' }])
        .png()
        .toFile(outputFileRound);
      
      console.log(`✓ Created ${outputFileRound} (${size}x${size})`);
    } catch (error) {
      console.error(`✗ Error creating ${density} icons:`, error.message);
    }
  }
  
  console.log('\n✓ Icon conversion complete!');
  console.log('Your app icons have been generated in all required densities.');
  console.log('The icons are ready to use in your Android app.');
}

convertIcons().catch(error => {
  console.error('Error during conversion:', error);
  process.exit(1);
});
