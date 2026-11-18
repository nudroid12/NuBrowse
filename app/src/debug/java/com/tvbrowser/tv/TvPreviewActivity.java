package com.tvbrowser.tv;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;

import com.nudroidlabs.tvadapter.TvAdapter;
import com.nudroidlabs.tvadapter.TvPreviewDisplay;

public final class TvPreviewActivity extends MainActivity {
    private TvPreviewDisplay previewDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Context host = getBaseContext();
        if (!TvAdapter.isTelevision(host)) {
            previewDisplay = TvPreviewDisplay.fromProfile(host, null);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public Resources getResources() {
        return previewDisplay == null ? super.getResources() : previewDisplay.resources();
    }

    @Override
    public void setContentView(View content) {
        super.setContentView(previewDisplay == null
                ? content : new TvAdapter(this, content, previewDisplay));
    }
}
