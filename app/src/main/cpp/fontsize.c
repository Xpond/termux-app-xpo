/**
 * XPort FontSize Command
 * 
 * Simple command-line tool to adjust terminal font size
 * Communicates with the XPort terminal app via file-based interface
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#define FONTSIZE_FILE "/data/data/com.xport.terminal/files/home/.fontsize"
#define FONTSIZE_TRIGGER "/data/data/com.xport.terminal/files/home/.fontsize_changed"

static void show_usage(const char *prog) {
    printf("XPort Terminal Font Size Manager\n");
    printf("\n");
    printf("Usage: %s [SCALE]\n", prog);
    printf("       %s --help\n", prog);
    printf("\n");
    printf("SCALE: Font size scale from 1 (smallest) to 10 (largest)\n");
    printf("       Default is 5 (medium)\n");
    printf("\n");
    printf("Without arguments, shows current font size scale.\n");
    printf("\n");
    printf("Examples:\n");
    printf("  %s          # Show current scale\n", prog);
    printf("  %s 3        # Set to scale 3 (small)\n", prog);
    printf("  %s 7        # Set to scale 7 (large)\n", prog);
}

static int get_current_scale(void) {
    FILE *f = fopen(FONTSIZE_FILE, "r");
    if (!f) {
        return 5; // Default scale
    }
    
    int scale = 5;
    if (fscanf(f, "%d", &scale) != 1 || scale < 1 || scale > 10) {
        scale = 5;
    }
    
    fclose(f);
    return scale;
}

static int set_scale(int scale) {
    if (scale < 1 || scale > 10) {
        fprintf(stderr, "Error: Scale must be between 1 and 10\n");
        return 1;
    }
    
    // Create directory if it doesn't exist
    char dir[] = "/data/data/com.xport.terminal/files/home";
    mkdir(dir, 0755);
    
    // Write scale to file
    FILE *f = fopen(FONTSIZE_FILE, "w");
    if (!f) {
        perror("Error: Cannot write font size file");
        return 1;
    }
    
    fprintf(f, "%d", scale);
    fclose(f);
    
    // Create trigger file to notify terminal app
    f = fopen(FONTSIZE_TRIGGER, "w");
    if (f) {
        fprintf(f, "%d", scale);
        fclose(f);
    }
    
    printf("Font size set to scale %d (%dpx)\n", scale, 8 + (scale * 3));
    printf("Changes will take effect on next terminal refresh.\n");
    
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc == 1) {
        // Show current scale
        int scale = get_current_scale();
        printf("Current font size: scale %d (%dpx)\n", scale, 8 + (scale * 3));
        return 0;
    }
    
    if (argc == 2) {
        if (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0) {
            show_usage(argv[0]);
            return 0;
        }
        
        // Set scale
        int scale = atoi(argv[1]);
        if (scale == 0 && strcmp(argv[1], "0") != 0) {
            fprintf(stderr, "Error: Invalid scale '%s'\n", argv[1]);
            show_usage(argv[0]);
            return 1;
        }
        
        return set_scale(scale);
    }
    
    fprintf(stderr, "Error: Too many arguments\n");
    show_usage(argv[0]);
    return 1;
}