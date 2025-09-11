/**
 * XPort BackgroundColor Command
 * 
 * Simple command-line tool to adjust terminal background color
 * Communicates with the XPort terminal app via file-based interface
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <ctype.h>

#define COLOR_CONFIG_FILE "/data/data/com.xport.terminal/files/home/.xport_colors"
#define COLOR_TRIGGER "/data/data/com.xport.terminal/files/home/.color_changed"

static void show_usage(const char *prog) {
    printf("XPort Terminal Background Color Manager\n");
    printf("\n");
    printf("Usage: %s [COLOR]\n", prog);
    printf("       %s --help\n", prog);
    printf("       %s --reset\n", prog);
    printf("\n");
    printf("COLOR: Hex color code (with or without #)\n");
    printf("       Examples: #000000, 000000, #001122, 112233\n");
    printf("\n");
    printf("Without arguments, shows current background color.\n");
    printf("\n");
    printf("Options:\n");
    printf("  --reset    Reset to default black (#FF000000)\n");
    printf("  --help     Show this help message\n");
    printf("\n");
    printf("Examples:\n");
    printf("  %s              # Show current background color\n", prog);
    printf("  %s 000000       # Set to black\n", prog);
    printf("  %s '#112233'    # Set to dark blue-gray (quote to prevent shell issues)\n", prog);
    printf("  %s --reset      # Reset to default black\n", prog);
}

static int is_valid_hex_char(char c) {
    return (c >= '0' && c <= '9') || 
           (c >= 'A' && c <= 'F') || 
           (c >= 'a' && c <= 'f');
}

static int validate_hex_color(const char *hex) {
    if (!hex) return 0;
    
    // Remove # if present
    if (hex[0] == '#') hex++;
    
    int len = strlen(hex);
    if (len != 6 && len != 8) return 0;  // RGB or ARGB
    
    for (int i = 0; i < len; i++) {
        if (!is_valid_hex_char(hex[i])) return 0;
    }
    
    return 1;
}

static void get_current_color(void) {
    FILE *f = fopen(COLOR_CONFIG_FILE, "r");
    if (!f) {
        printf("Current background color: #FF000000 (default black)\n");
        return;
    }
    
    char line[256];
    char current_color[16] = "FF000000";  // default black
    
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "background_color=", 17) == 0) {
            // Parse the color value
            char *color_str = strchr(line, '=');
            if (color_str) {
                color_str++; // Skip the '='
                unsigned int color_val;
                if (sscanf(color_str, "%u", &color_val) == 1) {
                    sprintf(current_color, "%08X", color_val);
                }
            }
            break;
        }
    }
    
    fclose(f);
    printf("Current background color: #%s\n", current_color);
}

static int set_color(const char *hex_color) {
    if (!validate_hex_color(hex_color)) {
        fprintf(stderr, "Error: Invalid hex color format '%s'\n", hex_color);
        fprintf(stderr, "Expected format: RRGGBB or AARRGGBB (with or without #)\n");
        return 1;
    }
    
    // Create directory if it doesn't exist
    char dir[] = "/data/data/com.xport.terminal/files/home";
    mkdir(dir, 0755);
    
    // Prepare hex string
    const char *hex = hex_color;
    if (hex[0] == '#') hex++;  // Remove # if present
    
    // Convert to integer
    unsigned int color_val;
    if (strlen(hex) == 6) {
        // RGB format - add full alpha
        sscanf(hex, "%x", &color_val);
        color_val |= 0xFF000000;  // Add full alpha
    } else {
        // ARGB format
        sscanf(hex, "%x", &color_val);
    }
    
    // Read existing config or create new one
    FILE *f = fopen(COLOR_CONFIG_FILE, "r");
    char text_line[256] = "";
    
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            if (strncmp(line, "text_color=", 11) == 0) {
                strcpy(text_line, line);
                break;
            }
        }
        fclose(f);
    }
    
    // Write new config
    f = fopen(COLOR_CONFIG_FILE, "w");
    if (!f) {
        perror("Error: Cannot write color config file");
        return 1;
    }
    
    fprintf(f, "# XPort Terminal Color Configuration\n");
    if (strlen(text_line) > 0) {
        fputs(text_line, f);
    }
    fprintf(f, "background_color=%u\n", color_val);
    
    fclose(f);
    
    // Create trigger file to notify terminal app
    f = fopen(COLOR_TRIGGER, "w");
    if (f) {
        fprintf(f, "background_color");
        fclose(f);
    }
    
    printf("Background color set to #%08X\n", color_val);
    printf("Changes will take effect on next terminal refresh.\n");
    
    return 0;
}

static int reset_color(void) {
    return set_color("000000");  // Reset to black
}

int main(int argc, char *argv[]) {
    if (argc == 1) {
        // Show current color
        get_current_color();
        return 0;
    }
    
    if (argc == 2) {
        if (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0) {
            show_usage(argv[0]);
            return 0;
        }
        
        if (strcmp(argv[1], "--reset") == 0) {
            return reset_color();
        }
        
        // Set color
        return set_color(argv[1]);
    }
    
    fprintf(stderr, "Error: Too many arguments\n");
    show_usage(argv[0]);
    return 1;
}