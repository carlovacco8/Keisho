// Keisho — Android port: result of a self-update install session.
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.carlovacco8.keisho;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's own "Install / Update?" confirmation screen.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.w("KeishoUpdate", "install status " + status + ": " + message);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(context, "Aggiornamento non riuscito"
                        + (message != null ? ": " + message : ""), Toast.LENGTH_LONG).show();
            }
        }
    }
}
