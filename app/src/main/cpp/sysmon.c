/*
 * xport System Monitor (sysmon)
 *
 * Thin launcher: parses flags and fires an Android broadcast that the xport
 * app receives to print real system stats. The stat gathering lives in the
 * Java BroadcastReceiver (com.xport.terminal.SYSMON), not here, because most
 * of /proc is restricted to apps on Android and only the app context can
 * reach the data reliably.
 *
 * Reconstructed from the shipped binary (DWARF + .rodata) after the original
 * source was lost; behaviour is byte-faithful to the installed build.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static void show_usage(const char *prog) {
    printf("xport System Monitor\n");
    printf("Usage: %s [OPTIONS]\n", prog);
    printf("Options:\n");
    printf("  --once     Show stats once and exit (default)\n");
    printf("  --compact  Compact output format\n");
    printf("  --help     Show this help message\n");
    printf("\n");
    printf("Shows system statistics from accessible sources:\n");
    printf("- Memory usage from /proc/meminfo\n");
    printf("- System uptime from /proc/uptime\n");
    printf("- CPU info from /proc/cpuinfo\n");
    printf("- Load/Network: Blocked by Android\n");
    printf("\n");
    printf("       %s --help\n", prog);
}

int main(int argc, char *argv[]) {
    int compact = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--compact") == 0) {
            compact = 1;
        } else if (strcmp(argv[i], "--once") == 0) {
            /* default behaviour, nothing to set */
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            show_usage(argv[0]);
            return 0;
        } else {
            fprintf(stderr, "Unknown option: %s\n", argv[i]);
            show_usage(argv[0]);
            return 1;
        }
    }

    if (compact) {
        system("am broadcast -a com.xport.terminal.SYSMON --es format compact");
    } else {
        system("am broadcast -a com.xport.terminal.SYSMON --es format full");
    }

    return 0;
}
