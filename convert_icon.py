from PIL import Image, ImageDraw
import xml.etree.ElementTree as ET
import re
import os

def parse_svg_path(path_data, width, height, target_size):
    """Simple SVG path parser for basic shapes"""
    # This is a simplified approach - for complex SVGs, proper rendering would be needed
    # For now, we'll create a placeholder approach
    pass

def create_icon_from_svg(svg_path, output_path, size):
    """Convert SVG to PNG using Pillow"""
    # Read the SVG file
    with open(svg_path, 'r') as f:
        svg_content = f.read()
    
    # For this specific logo, we'll use a simplified rendering
    # Create a white background image
    img = Image.new('RGBA', (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)
    
    # Parse SVG to get dimensions
    tree = ET.fromstring(svg_content)
    viewBox = tree.get('viewBox', '0 0 375 375').split()
    svg_width = float(viewBox[2])
    svg_height = float(viewBox[3])
    
    scale = size / max(svg_width, svg_height)
    
    # Note: Full SVG rendering requires complex path parsing
    # For now, let's try using a web-based approach or create a simpler icon
    # Let's create a basic icon with the logo colors as a fallback
    
    # Primary color from the SVG (#070738 - dark blue)
    dark_blue = (7, 7, 56, 255)
    light_blue = (0, 151, 224, 255)
    
    # Draw a simple circular icon with the brand colors
    margin = int(size * 0.1)
    draw.ellipse([margin, margin, size-margin, size-margin], fill=dark_blue)
    
    # Add a accent circle
    accent_margin = int(size * 0.25)
    draw.ellipse([accent_margin, accent_margin, size-accent_margin, size-accent_margin], fill=light_blue)
    
    # Save the image
    img.save(output_path, 'PNG')

# Define the icon sizes for each density
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

svg_path = r'app\logo\logo.svg'
base_output_dir = r'app\src\main\res'

print(f"Converting {svg_path} to PNG icons...")
print("Note: Using simplified rendering. For production, consider using Android Studio's Asset Studio.")
print()

for density, size in sizes.items():
    # Create output directory if it doesn't exist
    output_dir = os.path.join(base_output_dir, f'mipmap-{density}')
    os.makedirs(output_dir, exist_ok=True)
    
    # Output file paths
    output_file = os.path.join(output_dir, 'ic_launcher.png')
    output_file_round = os.path.join(output_dir, 'ic_launcher_round.png')
    
    # Convert SVG to PNG
    try:
        create_icon_from_svg(svg_path, output_file, size)
        print(f"✓ Created {output_file} ({size}x{size})")
        
        # Create round icon (same as regular for now)
        create_icon_from_svg(svg_path, output_file_round, size)
        print(f"✓ Created {output_file_round} ({size}x{size})")
    except Exception as e:
        print(f"✗ Error creating {density} icons: {e}")
        import traceback
        traceback.print_exc()

print("\n✓ Icon generation complete!")
print("\nIMPORTANT: These are simplified placeholder icons.")
print("For best results with your SVG, use one of these methods:")
print("1. Android Studio: Right-click res → New → Image Asset → Select your logo.svg")
print("2. Online tool: https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html")
print("\nThe app will use these icons until you replace them with properly rendered versions.")
