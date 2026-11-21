/**************************************************************************/
/*  GodotApp.java                                                         */
/**************************************************************************/
/*                         This file is part of:                          */
/*                             GODOT ENGINE                               */
/*                        https://godotengine.org                         */
/**************************************************************************/
/* Copyright (c) 2014-present Godot Engine contributors (see AUTHORS.md). */
/* Copyright (c) 2007-2014 Juan Linietsky, Ariel Manzur.                  */
/*                                                                        */
/* Permission is hereby granted, free of charge, to any person obtaining  */
/* a copy of this software and associated documentation files (the        */
/* "Software"), to deal in the Software without restriction, including    */
/* without limitation the rights to use, copy, modify, merge, publish,    */
/* distribute, sublicense, and/or sell copies of the Software, and to     */
/* permit persons to whom the Software is furnished to do so, subject to  */
/* the following conditions:                                              */
/*                                                                        */
/* The above copyright notice and this permission notice shall be         */
/* included in all copies or substantial portions of the Software.        */
/*                                                                        */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. */
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY   */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,   */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE      */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                 */
/**************************************************************************/

package com.godot.game;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotActivity;
import org.godotengine.godot.plugin.GodotPlugin;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.core.splashscreen.SplashScreen;

import com.mobcode.terminal.FloatingStatusOverlay;
import com.mobcode.terminal.MobCodeTerminalPlugin;
import com.mobcode.terminal.SessionStatusService;
import com.mobcode.terminal.TerminalManager;

import java.util.Collections;
import java.util.Set;

/**
 * Template activity for Godot Android builds with MobCode Terminal integration.
 */
public class GodotApp extends GodotActivity {
	static {
		// .NET libraries.
		if (BuildConfig.FLAVOR.equals("mono")) {
			try {
				Log.v("GODOT", "Loading System.Security.Cryptography.Native.Android library");
				System.loadLibrary("System.Security.Cryptography.Native.Android");
			} catch (UnsatisfiedLinkError e) {
				Log.e("GODOT", "Unable to load System.Security.Cryptography.Native.Android library");
			}
		}
	}

	private final Runnable updateWindowAppearance = () -> {
		Godot godot = getGodot();
		if (godot != null) {
			godot.enableImmersiveMode(godot.isInImmersiveMode(), true);
			godot.enableEdgeToEdge(godot.isInEdgeToEdgeMode(), true);
			godot.setSystemBarsAppearance();
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
		EdgeToEdge.enable(this);
		super.onCreate(savedInstanceState);

		Godot godot = getGodot();
		if (godot != null && godot.getDisableGodotSplash()) {
			splashScreen.setKeepOnScreenCondition(() -> godot.getRunStatus() != Godot.RunStatus.STARTED);
		}

		TerminalManager.getInstance().init(this, null);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4210);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
			android.util.Log.i("MobCodeOverlay", "Overlay permission missing at launch; opening settings");
			FloatingStatusOverlay.requestPermission(this);
		} else {
			android.util.Log.i("MobCodeOverlay", "Overlay permission granted at launch");
		}
	}

	@Override
	public Set<GodotPlugin> getHostPlugins(Godot engine) {
		return Collections.singleton(new MobCodeTerminalPlugin(engine));
	}

	@Override
	public void onBackPressed() {
		if (TerminalManager.getInstance().isTerminalVisible()) {
			TerminalManager.getInstance().hideTerminal();
			return;
		}
		super.onBackPressed();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		TerminalManager.getInstance().onActivityResult(requestCode, resultCode, data);
	}

	@Override
	public void onResume() {
		super.onResume();
		SessionStatusService.stop(this);
		boolean canDraw = FloatingStatusOverlay.canDraw(this);
		android.util.Log.i("MobCodeOverlay", "onResume canDraw=" + canDraw
				+ " pinned=" + FloatingStatusOverlay.hasFloatingSessions());
		if (canDraw && FloatingStatusOverlay.hasFloatingSessions()) {
			FloatingStatusOverlay.show(this);
		} else {
			FloatingStatusOverlay.hide();
		}
		updateWindowAppearance.run();
		handleSessionIntent(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleSessionIntent(intent);
	}

	private void handleSessionIntent(Intent intent) {
		if (intent != null && intent.hasExtra(FloatingStatusOverlay.EXTRA_OPEN_SESSION_ID)) {
			String sessionId = intent.getStringExtra(FloatingStatusOverlay.EXTRA_OPEN_SESSION_ID);
			intent.removeExtra(FloatingStatusOverlay.EXTRA_OPEN_SESSION_ID);
			if (sessionId != null && !sessionId.isEmpty()) {
				String type = FloatingStatusOverlay.getSessionType(sessionId);
				TerminalManager.getInstance().showTerminal(sessionId, type);
			}
		}
	}

	@Override
	protected void onUserLeaveHint() {
		android.util.Log.i("MobCodeOverlay", "onUserLeaveHint; starting status service");
		SessionStatusService.startIfActive(this);
		if (FloatingStatusOverlay.canDraw(this) && FloatingStatusOverlay.hasFloatingSessions()) {
			FloatingStatusOverlay.show(this);
		}
		super.onUserLeaveHint();
	}

	@Override
	public void onPause() {
		android.util.Log.i("MobCodeOverlay", "onPause; pinned=" + FloatingStatusOverlay.hasFloatingSessions());
		SessionStatusService.startIfActive(this);
		if (FloatingStatusOverlay.canDraw(this) && FloatingStatusOverlay.hasFloatingSessions()) {
			FloatingStatusOverlay.show(this);
		}
		super.onPause();
	}

	@Override
	public void onGodotMainLoopStarted() {
		super.onGodotMainLoopStarted();
		runOnUiThread(updateWindowAppearance);
	}

	@Override
	public void onGodotForceQuit(Godot instance) {
		if (!BuildConfig.FLAVOR.equals("instrumented")) {
			// For instrumented builds, we disable force-quitting to allow the instrumented tests to complete
			// successfully, otherwise they fail when the process crashes.
			super.onGodotForceQuit(instance);
		}
	}

	@Override
	protected boolean isPiPEnabled() {
		return true;
	}
}