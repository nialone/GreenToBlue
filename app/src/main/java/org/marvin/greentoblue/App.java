package org.marvin.greentoblue;

import android.app.Application;

public class App extends Application
{
    protected static App instance;

    @Override
    public void onCreate()
    {
        super.onCreate();
        instance = this;
    }

    public static String getReStr(int value)
    {
        return instance.getResources().getString(value);
    }
}