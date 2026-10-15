package com.padlayer;

import android.app.Application;

import org.conscrypt.Conscrypt;

import java.security.Security;

public final class PadlayerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
    }
}
