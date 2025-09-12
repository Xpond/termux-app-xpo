/**
 * xport Font Command
 * 
 * Simple command-line tool to change terminal font variants
 * Communicates with the xport terminal app via file-based interface
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#define FONT_FILE "/data/data/com.xport.terminal/files/home/.font"
#define FONT_TRIGGER "/data/data/com.xport.terminal/files/home/.font_changed"

// Available font variants
#define FONT_GEIST_REGULAR "geistmono-regular"
#define FONT_GEIST_BOLD "geistmono-bold"
#define FONT_GEIST_ITALIC "geistmono-italic"
#define FONT_INTER_REGULAR "inter-regular"
#define FONT_INTER_BOLD "inter-bold"
#define FONT_INTER_ITALIC "inter-italic"

static void show_usage(const char *prog) {
    printf("xport Terminal Font Manager\n");
    printf("\n");
    printf("Usage: %s [FONT_VARIANT]\n", prog);
    printf("       %s --help\n", prog);
    printf("\n");
    printf("FONT_VARIANT: Available font variants:\n");
    printf("  Geist Mono:\n");
    printf("    %s     # Geist Mono Regular (default)\n", FONT_GEIST_REGULAR);
    printf("    %s       # Geist Mono Bold\n", FONT_GEIST_BOLD);
    printf("    %s      # Geist Mono Italic\n", FONT_GEIST_ITALIC);
    printf("  Inter:\n");
    printf("    %s        # Inter Regular\n", FONT_INTER_REGULAR);
    printf("    %s          # Inter Bold\n", FONT_INTER_BOLD);
    printf("    %s        # Inter Italic\n", FONT_INTER_ITALIC);
    printf("\n");
    printf("Without arguments, shows current font and all available fonts.\n");
    printf("\n");
    printf("Examples:\n");
    printf("  %s                         # Show current font and list all\n", prog);
    printf("  %s %s      # Set to Geist italic\n", prog, FONT_GEIST_ITALIC);
    printf("  %s %s          # Set to Inter bold\n", prog, FONT_INTER_BOLD);
    printf("  %s %s        # Set to Inter regular\n", prog, FONT_INTER_REGULAR);
}

static const char* get_font_display_name(const char* font_variant) {
    if (strcmp(font_variant, FONT_GEIST_REGULAR) == 0) {
        return "Geist Mono Regular";
    } else if (strcmp(font_variant, FONT_GEIST_BOLD) == 0) {
        return "Geist Mono Bold";
    } else if (strcmp(font_variant, FONT_GEIST_ITALIC) == 0) {
        return "Geist Mono Italic";
    } else if (strcmp(font_variant, FONT_INTER_REGULAR) == 0) {
        return "Inter Regular";
    } else if (strcmp(font_variant, FONT_INTER_BOLD) == 0) {
        return "Inter Bold";
    } else if (strcmp(font_variant, FONT_INTER_ITALIC) == 0) {
        return "Inter Italic";
    } else {
        return "Unknown";
    }
}

static int is_valid_font(const char* font_variant) {
    return (strcmp(font_variant, FONT_GEIST_REGULAR) == 0 ||
            strcmp(font_variant, FONT_GEIST_BOLD) == 0 ||
            strcmp(font_variant, FONT_GEIST_ITALIC) == 0 ||
            strcmp(font_variant, FONT_INTER_REGULAR) == 0 ||
            strcmp(font_variant, FONT_INTER_BOLD) == 0 ||
            strcmp(font_variant, FONT_INTER_ITALIC) == 0);
}

static const char* get_current_font(void) {
    static char font_buffer[32];
    FILE *f = fopen(FONT_FILE, "r");
    if (!f) {
        return FONT_GEIST_REGULAR; // Default font
    }
    
    if (fgets(font_buffer, sizeof(font_buffer), f) != NULL) {
        // Remove newline if present
        font_buffer[strcspn(font_buffer, "\n")] = 0;
        
        // Validate font variant
        if (is_valid_font(font_buffer)) {
            fclose(f);
            return font_buffer;
        }
    }
    
    fclose(f);
    return FONT_GEIST_REGULAR; // Default if invalid or empty
}

static int set_font(const char* font_variant) {
    if (!is_valid_font(font_variant)) {
        fprintf(stderr, "Error: Invalid font variant '%s'\n", font_variant);
        fprintf(stderr, "Available variants:\n");
        fprintf(stderr, "  Geist Mono: %s, %s, %s\n", 
                FONT_GEIST_REGULAR, FONT_GEIST_BOLD, FONT_GEIST_ITALIC);
        fprintf(stderr, "  Inter: %s, %s, %s\n", 
                FONT_INTER_REGULAR, FONT_INTER_BOLD, FONT_INTER_ITALIC);
        return 1;
    }
    
    // Create directory if it doesn't exist
    char dir[] = "/data/data/com.xport.terminal/files/home";
    mkdir(dir, 0755);
    
    // Write font variant to file
    FILE *f = fopen(FONT_FILE, "w");
    if (!f) {
        perror("Error: Cannot write font file");
        return 1;
    }
    
    fprintf(f, "%s", font_variant);
    fclose(f);
    
    // Create trigger file to notify terminal app
    f = fopen(FONT_TRIGGER, "w");
    if (f) {
        fprintf(f, "%s", font_variant);
        fclose(f);
    }
    
    printf("Font set to %s\n", get_font_display_name(font_variant));
    printf("Changes will take effect on next terminal refresh.\n");
    
    return 0;
}

static void show_all_fonts() {
    const char* current_font = get_current_font();
    
    printf("Current font: %s (%s)\n\n", 
           get_font_display_name(current_font), current_font);
    
    printf("Available fonts:\n");
    
    // Geist Mono family
    printf("  Geist Mono:\n");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_GEIST_REGULAR) == 0) ? "* " : "  ",
           FONT_GEIST_REGULAR, 
           (strcmp(current_font, FONT_GEIST_REGULAR) == 0) ? " (current)" : "",
           "# Geist Mono Regular");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_GEIST_BOLD) == 0) ? "* " : "  ",
           FONT_GEIST_BOLD,
           (strcmp(current_font, FONT_GEIST_BOLD) == 0) ? " (current)" : "",
           "# Geist Mono Bold");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_GEIST_ITALIC) == 0) ? "* " : "  ",
           FONT_GEIST_ITALIC,
           (strcmp(current_font, FONT_GEIST_ITALIC) == 0) ? " (current)" : "",
           "# Geist Mono Italic");
    
    // Inter family
    printf("  Inter:\n");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_INTER_REGULAR) == 0) ? "* " : "  ",
           FONT_INTER_REGULAR,
           (strcmp(current_font, FONT_INTER_REGULAR) == 0) ? " (current)" : "",
           "# Inter Regular");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_INTER_BOLD) == 0) ? "* " : "  ",
           FONT_INTER_BOLD,
           (strcmp(current_font, FONT_INTER_BOLD) == 0) ? " (current)" : "",
           "# Inter Bold");
    printf("    %s%-18s%s %s\n", 
           (strcmp(current_font, FONT_INTER_ITALIC) == 0) ? "* " : "  ",
           FONT_INTER_ITALIC,
           (strcmp(current_font, FONT_INTER_ITALIC) == 0) ? " (current)" : "",
           "# Inter Italic");
}

int main(int argc, char *argv[]) {
    if (argc == 1) {
        // Show current font and all available fonts
        show_all_fonts();
        return 0;
    }
    
    if (argc == 2) {
        if (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0) {
            show_usage(argv[0]);
            return 0;
        }
        
        // Set font variant
        return set_font(argv[1]);
    }
    
    fprintf(stderr, "Error: Too many arguments\n");
    show_usage(argv[0]);
    return 1;
}