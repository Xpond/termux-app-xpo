package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.logger.Logger;

public class SysmonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.xport.terminal.SYSMON".equals(intent.getAction())) {
            try {
                boolean compact = "compact".equals(intent.getStringExtra("format"));
                String info = new SystemMonitor(context).getSystemInfo(compact);
                Logger.logInfo("SysmonReceiver", "SYSMON OUTPUT:\n" + info);
            } catch (Exception e) {
                Logger.logError("SysmonReceiver", "Error in sysmon receiver: " + e.getMessage());
            }
        }
    }
}
