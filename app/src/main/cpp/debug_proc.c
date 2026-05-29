/*
 * debug_proc - dump accessible /proc files on Android
 *
 * Diagnostic helper used while building sysmon: prints what each /proc source
 * actually returns under Android app restrictions, so we can tell which stats
 * are reachable and which are blocked.
 *
 * Reconstructed from the shipped binary (DWARF + .rodata) after the original
 * source was lost; behaviour is faithful to the installed build.
 */
#include <stdio.h>

int main(void) {
    char line[256];
    FILE *fp;
    int i;

    printf("=== DEBUGGING /proc FILES ON ANDROID ===\n");

    /* /proc/meminfo - first 10 lines */
    puts("--- /proc/meminfo (first 10 lines) ---");
    fp = fopen("/proc/meminfo", "r");
    if (fp) {
        i = 0;
        while (i < 10 && fgets(line, sizeof(line), fp)) {
            printf("meminfo[%d]: %s", i, line);
            i++;
        }
        fclose(fp);
    } else {
        puts("CANNOT OPEN /proc/meminfo");
    }

    /* /proc/loadavg - single line */
    puts("--- /proc/loadavg ---");
    fp = fopen("/proc/loadavg", "r");
    if (fp) {
        if (fgets(line, sizeof(line), fp))
            printf("loadavg: %s", line);
        fclose(fp);
    } else {
        puts("CANNOT OPEN /proc/loadavg");
    }

    /* /proc/net/dev */
    puts("--- /proc/net/dev ---");
    fp = fopen("/proc/net/dev", "r");
    if (fp) {
        i = 0;
        while (fgets(line, sizeof(line), fp)) {
            printf("netdev[%d]: %s", i, line);
            i++;
        }
        fclose(fp);
    } else {
        puts("CANNOT OPEN /proc/net/dev");
    }

    /* /proc/cpuinfo */
    puts("--- /proc/cpuinfo ---");
    fp = fopen("/proc/cpuinfo", "r");
    if (fp) {
        i = 0;
        while (fgets(line, sizeof(line), fp)) {
            printf("cpuinfo[%d]: %s", i, line);
            i++;
        }
        fclose(fp);
    } else {
        puts("CANNOT OPEN /proc/cpuinfo");
    }

    return 0;
}
