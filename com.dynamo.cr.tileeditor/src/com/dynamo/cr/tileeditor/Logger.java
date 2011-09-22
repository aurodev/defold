package com.dynamo.cr.tileeditor;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.statushandlers.StatusManager;

import com.dynamo.cr.tileeditor.core.ILogger;

public class Logger implements ILogger {

    @Override
    public void logException(Throwable exception) {
        Status status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, exception.getMessage(), exception);
        StatusManager.getManager().handle(status, StatusManager.LOG);
    }

}
