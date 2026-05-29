package com.termux.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Process;

import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

public class SystemMonitor {

    private final Context mContext;

    public SystemMonitor(Context context) {
        this.mContext = context;
    }

    private String mapCpuPart(String part) {
        if (part == null || part.isEmpty()) {
            return "";
        }
        switch (part.toLowerCase()) {
            case "0xd05": return "ARM Cortex-A55";
            case "0xd0a": return "ARM Cortex-A75";
            case "0xd0b": return "ARM Cortex-A76";
            case "0xd0c": return "ARM Cortex-A77";
            case "0xd0d": return "ARM Cortex-A78";
            case "0xd40": return "ARM Neoverse-N1";
            case "0xd44": return "ARM Cortex-X1";
            case "0xd46": return "ARM Cortex-A510";
            case "0xd47": return "ARM Cortex-A710";
            case "0xd48": return "ARM Cortex-X2";
            default: return "ARM CPU (" + part + ")";
        }
    }

    public String getBatteryInfo() {
        try {
            Intent status = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) {
                return "Battery: N/A";
            }
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (level == -1 || scale == -1) {
                return "Battery: N/A";
            }
            float pct = (level * 100) / (float) scale;
            boolean charging = state == BatteryManager.BATTERY_STATUS_CHARGING
                || state == BatteryManager.BATTERY_STATUS_FULL;
            String suffix = "";
            if (charging) {
                if (plugged == BatteryManager.BATTERY_PLUGGED_AC) {
                    suffix = " (AC)";
                } else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                    suffix = " (USB)";
                } else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                    suffix = " (Wireless)";
                } else {
                    suffix = " (Charging)";
                }
            }
            return String.format("Battery: %.0f%%%s", pct, suffix);
        } catch (Exception e) {
            Logger.logError("SystemMonitor", "Error getting battery info: " + e.getMessage());
            return "Battery: Error";
        }
    }

    public String getCpuInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String part = "";
            String hardware = "";
            String arch = "";
            int cores = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("processor")) {
                    cores++;
                } else if (line.startsWith("Hardware") && hardware.isEmpty()) {
                    String[] kv = line.split(":");
                    if (kv.length > 1) hardware = kv[1].trim();
                } else if (line.startsWith("CPU architecture")) {
                    String[] kv = line.split(":");
                    if (kv.length > 1) arch = kv[1].trim();
                } else if (line.startsWith("CPU part")) {
                    String[] kv = line.split(":");
                    if (kv.length > 1) part = kv[1].trim();
                }
            }
            reader.close();
            String mapped = mapCpuPart(part);
            if (!mapped.isEmpty() || hardware.isEmpty()) {
                hardware = mapped;
            }
            if (hardware.isEmpty()) {
                hardware = "ARM Processor";
            }
            if (!arch.isEmpty()) {
                hardware = hardware + " ARMv" + arch;
            }
            return String.format("CPU: %d cores - %s", cores, hardware);
        } catch (IOException e) {
            Logger.logError("SystemMonitor", "Error reading CPU info: " + e.getMessage());
            return "CPU: Error reading /proc/cpuinfo";
        }
    }

    public String getMemoryInfo() {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long total = mi.totalMem;
            long used = total - mi.availMem;
            double t = total;
            double u = used;
            return String.format("Memory: %.1f%% used (%.1f GB / %.1f GB)",
                (u / t) * 100.0d,
                ((u / 1024.0d) / 1024.0d) / 1024.0d,
                ((t / 1024.0d) / 1024.0d) / 1024.0d);
        } catch (Exception e) {
            Logger.logError("SystemMonitor", "Error getting memory info: " + e.getMessage());
            return "Memory: Error";
        }
    }

    public String getNetworkInfo() {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx != -1 && tx != -1) {
                return String.format("Network: ↓ %.1f MB received, ↑ %.1f MB transmitted",
                    (rx / 1024.0d) / 1024.0d, (tx / 1024.0d) / 1024.0d);
            }
            return "Network: Not supported on this device";
        } catch (Exception e) {
            Logger.logError("SystemMonitor", "Error getting network info: " + e.getMessage());
            return "Network: Error";
        }
    }

    public String getProcessInfo() {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            int count = procs != null ? procs.size() : 0;
            return String.format("Processes: %d visible, PID %d", count, Process.myPid());
        } catch (Exception e) {
            Logger.logError("SystemMonitor", "Error getting process info: " + e.getMessage());
            return "Processes: Error";
        }
    }

    public String getSystemInfo(boolean compact) {
        StringBuilder sb = new StringBuilder();
        if (compact) {
            sb.append("SYSMON ");
            String mem = getMemoryInfo();
            int pctIdx;
            if (mem.startsWith("Memory: ") && (pctIdx = mem.substring(8).indexOf('%')) > 0) {
                sb.append("MEM ").append(mem.substring(8).substring(0, pctIdx + 1)).append(" ");
            }
            String bat = getBatteryInfo();
            if (bat.startsWith("Battery: ")) {
                sb.append("BAT ").append(bat.substring(9)).append(" ");
            }
            String cpu = getCpuInfo();
            int coreIdx;
            if (cpu.startsWith("CPU: ") && (coreIdx = cpu.substring(5).indexOf(" cores")) > 0) {
                sb.append("CPU ").append(cpu.substring(5).substring(0, coreIdx)).append("c ");
            }
            String proc = getProcessInfo();
            if (proc.contains("PID ")) {
                sb.append("PID ").append(proc.substring(proc.indexOf("PID ") + 4));
            }
            return sb.toString();
        }
        sb.append("=== XPort System Monitor ===\n\n");
        sb.append(getMemoryInfo()).append("\n");
        sb.append(getBatteryInfo()).append("\n");
        sb.append(getCpuInfo()).append("\n");
        sb.append(getNetworkInfo()).append("\n");
        sb.append(getProcessInfo()).append("\n");
        sb.append(getUptimeInfo()).append("\n");
        return sb.toString();
    }

    public String getUptimeInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/uptime"));
            String line = reader.readLine();
            reader.close();
            if (line == null) {
                return "Uptime: N/A";
            }
            String[] parts = line.split(" ");
            if (parts.length <= 0) {
                return "Uptime: N/A";
            }
            double up = Double.parseDouble(parts[0]);
            int days = (int) (up / 86400.0d);
            double rem = up - (86400 * days);
            int hours = (int) (rem / 3600.0d);
            int minutes = (int) ((rem - (hours * 3600)) / 60.0d);
            return String.format("Uptime: %d days, %d hours, %d minutes", days, hours, minutes);
        } catch (Exception e) {
            Logger.logError("SystemMonitor", "Error getting uptime: " + e.getMessage());
            return "Uptime: Error";
        }
    }
}
