// ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
// Copyright (C) 2019 - 2021 University of Luxembourg
//
// This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
//
package org.droidmate.uiautomator2daemon.exploration

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.support.test.uiautomator.By
import android.support.test.uiautomator.UiDevice
import android.support.test.uiautomator.Until
import android.support.test.uiautomator.click
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.droidmate.deviceInterface.DeviceConstants
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.uiautomator2daemon.uiautomatorExtensions.*
import org.droidmate.uiautomator2daemon.uiautomatorExtensions.UiParser.Companion.computeIdHash
import org.droidmate.uiautomator2daemon.uiautomatorExtensions.UiSelector.actableAppElem
import org.droidmate.uiautomator2daemon.uiautomatorExtensions.UiSelector.isWebView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis


var idleTimeout: Long = 100
var interactiveTimeout: Long = 1000

var measurePerformance =	true

@Suppress("ConstantConditionIf")
inline fun <T> nullableDebugT(msg: String, block: () -> T?, timer: (Long) -> Unit = {}, inMillis: Boolean = false): T? {
	var res: T? = null
	if (measurePerformance) {
		measureNanoTime {
			res = block.invoke()
		}.let {
			timer(it)
			Log.d(DeviceConstants.deviceLogcatTagPrefix + "performance","TIME: ${if (inMillis) "${(it / 1000000.0).toInt()} ms" else "${it / 1000.0} ns/1000"} \t $msg")
		}
	} else res = block.invoke()
	return res
}

inline fun <T> debugT(msg: String, block: () -> T?, timer: (Long) -> Unit = {}, inMillis: Boolean = false): T {
	return nullableDebugT(msg, block, timer, inMillis) ?: throw RuntimeException("debugT is non nullable use nullableDebugT instead")
}

private const val logTag = DeviceConstants.deviceLogcatTagPrefix + "ActionExecution"

var lastId = 0
var isWithinQueue = false
@Suppress("DEPRECATION")
suspend fun ExplorationAction.execute(env: UiAutomationEnvironment): Any {
	val idMatch: (Int) -> SelectorCondition = {idHash ->{ n: AccessibilityNodeInfo, xPath ->
		val layer = env.lastWindows.find { it.w.windowId == n.windowId }?.layer ?: n.window?.layer
		layer != null && idHash == computeIdHash(xPath, layer)
	}}
	Log.d(logTag, "START execution ${toString()}($id)")
	val result: Any = when(this) { // REMARK this has to be an assignment for when to check for exhaustiveness
		is Click -> {
//			env.device.verifyCoordinate(x, y)
			/*env.device.executeShellCommand("input tap $x $y").apply {
				delay(delay)
			}*/
			env.device.click(x, y, interactiveTimeout)
//			delay(delay)
		}
		is Tick -> {
			var success = UiHierarchy.findAndPerform(env, idMatch(idHash)) {
				val newStatus = !it.isChecked
				it.isChecked = newStatus
				it.isChecked == newStatus
			}
			if (!success) {
				env.device.verifyCoordinate(x, y)
				success = env.device.click(x, y, interactiveTimeout)
			}
			delay(delay)
			success
		}
		is ClickEvent ->
			UiHierarchy.findAndPerform(env, idMatch(idHash)) { nodeInfo ->				// do this for API Level above 19 (exclusive)
//				Log.d(logTag, "looking for click target, windows are ${env.getDisplayedWindows()}")
				nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)}.also {
					if(it) {
						delay(delay)
						env.waitForWindowUpdate()
					} // wait for display update
					Log.d(logTag, "perform successful=$it")
				}
		is LongClickEvent -> UiHierarchy.findAndPerform(env, idMatch(idHash)) { nodeInfo ->				// do this for API Level above 19 (exclusive)
			nodeInfo.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)}.also {
			if(it) {
//				delay(delay)
				env.waitForWindowUpdate()
			} // wait for display update
			Log.d(logTag, "perform successful=$it")
		}
		is LongClick -> {
			env.device.verifyCoordinate(x, y)
//			env.device.pressBack()
//			env.device.click(x, y, interactiveTimeout).apply {
//				delay(delay)
//			}
			env.device.swipe(x,y,x,y,100).also {
//				delay(delay)
				env.waitForWindowUpdate()
			}

//			env.device.longClick(x, y, interactiveTimeout).apply {
//				delay(delay)
//			}
		}
		is GlobalAction ->
			when (actionType) {
				ActionType.PressBack -> env.device.pressBack()
				ActionType.PressHome -> env.device.pressHome()
				ActionType.PressMenu -> env.device.pressMenu()
				ActionType.PressEnter -> env.device.pressEnter()
				ActionType.EnableWifi, ActionType.EnableData -> {
					val wfm = env.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
					wfm.setWifiEnabled(true).also {
						if (!it) Log.w(logTag, "Failed to ensure WiFi is enabled!")
					}
				}
				ActionType.DisableWifi, ActionType.DisableData -> {
					val wfm = env.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
					wfm.setWifiEnabled(false).also {
						if (!it) Log.w(logTag, "Failed to ensure WiFi is disable!")
					}
				}
				ActionType.EnableAirplane -> {
					// read the airplane mode setting
					val isEnabled: Boolean = Settings.System.getInt(
							env.context.getContentResolver(),
							Settings.System.AIRPLANE_MODE_ON, 0) === 1

					// toggle airplane mode
					Settings.System.putInt(
							env.context.getContentResolver(),
							Settings.System.AIRPLANE_MODE_ON, if (isEnabled) 0 else 1)

					// Post an intent to reload
					val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
					intent.putExtra("state", !isEnabled)
					env.context.sendBroadcast(intent)
					true
				}
				ActionType.MinimizeMaximize -> {
					env.device.minimizeMaximize()
					true
				}
				ActionType.CloseKeyboard -> if (env.isKeyboardOpen()) //(UiHierarchy.any(env.device) { node, _ -> env.keyboardPkgs.contains(node.packageName) })
					env.device.pressBack()
				else true
				ActionType.FetchGUI -> fetchDeviceData(env = env, afterAction = false)
				ActionType.Terminate -> false /* should never be transferred to the device */
				else -> true
			}.also {
				if (it is Boolean && it) {
//					delay(200)
					env.waitForWindowUpdate()
				}
			}
		//.also { if (it is Boolean && it) { delay(idleTimeout) } }// wait for display update (if no Fetch action)
		is TextInsert ->
			UiHierarchy.findAndPerform(env, idMatch(idHash)) { nodeInfo ->
				if(nodeInfo.isFocusable){
					nodeInfo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
					Log.d(logTag,"focus input-field")
				}else if(nodeInfo.isClickable){
					nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
					Log.d(logTag, "click non-focusable input-field")
				}
				if(nodeInfo.isFocusable){
					nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
				}
				// do this for API Level above 19 (exclusive)
				val args = Bundle()
				args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
				nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also {
					//					if(it) { delay(idleTimeout) } // wait for display update
					Log.d(logTag, "perform successful=$it")
					if(sendEnter && !isWithinQueue){
						Log.d(logTag, "trigger enter")
						env.device.pressEnter()
					}  // when doing multiple action sending enter may trigger a continue button but not all elements are yet filled
//					delay(delay)
					env.waitForWindowUpdate()
				} }
		is RotateUI -> env.device.rotate(rotation, env.automation).also {
			env.waitForWindowUpdate()
		}
		is LaunchApp -> {
			env.device.pressKeyCode(KeyEvent.KEYCODE_WAKEUP)
			env.device.pressHome()
			//reset wifi
			val wfm = env.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
			wfm.setWifiEnabled(true).also {
				if (!it) Log.w(logTag, "Failed to ensure WiFi is enabled!")
			}

			/*// close notification bar
			val uiselector = UiSelector().packageName("com.android.systemui").resourceId("com.android.systemui:id/notification_stack_scroller")
			val notification_stack_scroller = env.device.findObject(uiselector)
			if (notification_stack_scroller.exists()) {
				env.device.openNotification()
			}*/

			//launch app
			env.automation.setRotation(0)
			if (env.isKeyboardOpen())
				env.device.pressBack()
			env.device.launchApp(packageName, env, launchActivityDelay, timeout)
			//reset rotaion

			true
		}
		is ResetApp -> {
			env.device.pressKeyCode(KeyEvent.KEYCODE_WAKEUP)
			env.device.pressHome()
			//reset wifi
            val wfm = env.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wfm.setWifiEnabled(true).also {
                if (!it) Log.w(logTag, "Failed to ensure WiFi is enabled!")
            }
			//launch app
			env.device.setOrientationNatural()
			if (env.isKeyboardOpen())
				env.device.pressBack()
			env.device.launchApp(packageName, env, launchActivityDelay, timeout)
			true
		}
		is Swipe -> env.device.twoPointAction(start,end){
			x0, y0, x1, y1 ->  env.device.swipe(x0, y0, x1, y1, stepSize).also {
				env.waitForWindowUpdate()
			}
		}
		is CallIntent -> env.device.sendIntent(action, category, uriString,activityName, packageName,env)
		is TwoPointerGesture ->	TODO("this requires a call on UiObject, which we currently do not match to our ui-extraction")
		is PinchIn -> TODO("this requires a call on UiObject, which we currently do not match to our ui-extraction")
		is PinchOut -> TODO("this requires a call on UiObject, which we currently do not match to our ui-extraction")
		is Scroll -> // TODO we may trigger the UiObject2 method instead
			UiHierarchy.findAndPerform(env, idMatch(idHash)) { nodeInfo ->				// do this for API Level above 19 (exclusive)
				when(direction) {
					Direction.LEFT,Direction.UP -> nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
					Direction.RIGHT,Direction.DOWN -> nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
				}
			}.also {
			if(it) {
				env.waitForWindowUpdate()
			} // wait for display update
			Log.d(logTag, "perform successful=$it")
		}
		is ActionQueue -> {
			var success = true
			isWithinQueue = true
			actions.forEachIndexed { i,action ->
				if(i==actions.size-1) isWithinQueue = false // reset var at the end of queue
				success = success &&
					action.execute(env).also{
						if(i<actions.size-1 &&
								((action is TextInsert && actions[i+1] is Click)
										|| action is Swipe)) {
							if(action is Swipe){
								Log.d(logTag, "delay after swipe")
								delay(delay)
							}
							getOrStoreImgPixels(env.captureScreen(), env, action.id)
						}
					}.let{ if(it is Boolean) it else true } }.apply{
				delay(delay)
				getOrStoreImgPixels(env.captureScreen(),env)
			}
		}
		else -> throw DeviceDaemonException("not implemented action $name was called in exploration/ActionExecution")
	}
	Log.d(logTag, "END execution of ${toString()} ($id)")
	return result
}


//REMARK keep the order of first wait for windowUpdate, then wait for idle, then extract windows to minimize synchronization issues with opening/closing keyboard windows
private suspend fun waitForSync(env: UiAutomationEnvironment, afterAction: Boolean, useDefault: Boolean) {
	val usingIdleTimeout = env.idleTimeout
	try {
		/*if (afterAction) {
			env.lastWindows.firstOrNull { it.isApp() && !it.isKeyboard && !it.isLauncher }?.let {
				env.device.waitForWindowUpdate(it.w.pkgName, env.interactiveTimeout) //wait sync on focused window
			}
		}*/
		debugT("wait for IDLE avg = ${time / max(1, cnt)} ms", {
//			env.device.waitForIdle(usingIdleTimeout)
			env.automation.waitForIdle(200,usingIdleTimeout)
			//		env.device.waitForIdle(env.idleTimeout) // this has a minimal delay of 500ms between events until the device is considered idle
		}, inMillis = true,
				timer = {
					Log.d(logTag, "time=${it} mms")
					time += it
					cnt += 1
				}) // this sometimes really sucks in performance but we do not yet have any reliable alternative
		debugOut("check if we have a webView", debugFetch)
		if (afterAction && UiHierarchy.any(env, cond = isWebView)) { // waitForIdle is insufficient for WebView's therefore we need to handle the stabilize separately
			debugOut("WebView detected wait for interactive element with different package name", debugFetch)
			UiHierarchy.waitFor(env, interactiveTimeout, actableAppElem)
		}
	} catch(e: java.util.concurrent.TimeoutException) {
		Log.e(logTag, "No idle state with idle timeout: 100ms within global timeout: ${usingIdleTimeout}ms", e)
	}
}


/** compressing an image no matter the quality, takes long time therefore the option of storing these asynchronous
 * and transferring them later is available via configuration
 */
private fun getOrStoreImgPixels(bm: Bitmap?, env: UiAutomationEnvironment, actionId: Int = lastId): ByteArray = debugT("wait for screen avg = ${wt / max(1, wc)} milliseconds",{
	when{ // if we couldn't capture screenshots
		bm == null ->{
			Log.w(logTag,"create empty image")
			ByteArray(0)
		}
		env.delayedImgTransfer ->{
			backgroundScope.launch(Dispatchers.IO){ // we could use an actor getting id and bitmap via channel, instead of starting another coroutine each time
				debugOut("create screenshot for action $actionId")
				val os = FileOutputStream(env.imgDir.absolutePath+ "/"+actionId+".jpg")
				bm.compress(Bitmap.CompressFormat.JPEG, env.imgQuality, os)
				os.close()
				bm.recycle()
			}
			ByteArray(0)
		}
		else -> UiHierarchy.compressScreenshot(bm).also{
			bm.recycle()
		}
	}
}, inMillis = true, timer = { wt += it ; wc += 1 })

private var time: Long = 0
private var cnt = 0
private var wt = 0.0
private var wc = 0
private const val debugFetch = false
private val isInteractive = { w: UiElementPropertiesI -> w.clickable || w.longClickable || w.checked!=null || w.isInputField}
suspend fun fetchDeviceData(env: UiAutomationEnvironment, afterAction: Boolean = false, useShortTimeout: Boolean=false): DeviceResponse = coroutineScope{
	debugOut("start fetch execution",debugFetch)
	waitForSync(env,afterAction,useShortTimeout)

	var windows: List<DisplayedWindow> = env.getDisplayedWindows()
	var isSuccessful = true

	// fetch the screenshot if available
	var img = env.captureScreen() // could maybe use Espresso View.DecorativeView to fetch screenshot instead

	debugOut("start element extraction",debugFetch)
	// we want the ui fetch first as it is fast but will likely solve synchronization issues
	var uiHierarchy: List<UiElementPropertiesI>? = null
	var fetchTime: Long=0
	debugT("Fetch UI", {
		uiHierarchy = UiHierarchy.fetch(windows,img).let{
			if(it == null ||  it.none (isInteractive) ) {
				if (it == null) {
					Log.w(logTag, "Could not parse current UI screen.")
				} else {
					Log.w(logTag, "No interactive widgets")
				}
				Log.w(logTag, "first ui extraction failed or no interactive elements were found \n $it, \n ---> start a second try")
				windows = env.getDisplayedWindows()
				img = env.captureScreen()
				UiHierarchy.fetch( windows, img ).also{ secondRes ->
					Log.d(logTag, "second try resulted in ${secondRes?.size} elements")
				}  //retry once for the case that AccessibilityNode tree was not yet stable
			} else it
		} ?: emptyList<UiElementPropertiesI>().also {
			isSuccessful = false
			Log.e(logTag, "could not parse current UI screen ( $windows )")
			throw java.lang.RuntimeException("UI extraction failed for windows: $windows")
		}
	},inMillis = true,timer = {
		fetchTime = it
	})

//	Log.d(logTag, "uiHierarchy = $uiHierarchy")
/*	uiHierarchy.also {
		debugOut("INTERACTIVE Element in UI = ${it.any (isInteractive)}")
	}*/

//			val xmlDump = UiHierarchy.getXml(device)
	val focusedWindow = windows.filter { it.isExtracted() && !it.isKeyboard }.let { appWindows ->
		( appWindows.firstOrNull{ it.w.hasFocus || it.w.hasInputFocus } ?: appWindows.firstOrNull())
	}
	val focusedAppPkg = focusedWindow	?.w?.pkgName ?: "no AppWindow detected"
	debugOut("determined focused window $focusedAppPkg inputF=${focusedWindow?.w?.hasInputFocus}, focus=${focusedWindow?.w?.hasFocus}")

	debugOut("started async ui extraction",debugFetch)

	debugOut("compute img pixels",debugFetch)
	val imgPixels =	getOrStoreImgPixels(img,env)

	var xml: String = "TODO parse widget list on Pc if we need the XML or introduce a debug property to enable parsing" +
			", because (currently) we would have to traverse the tree a second time"
//	if(debugEnabled) xml = UiHierarchy.getXml(env)

	env.lastResponse = DeviceResponse.create( isSuccessful = isSuccessful, uiHierarchy = uiHierarchy!!,
		uiDump = xml,
		launchedActivity = env.launchedMainActivity,
		capturedScreen = img != null,
		screenshot = imgPixels,
		appWindows = windows.mapNotNull { if(it.isExtracted()) it.w else null },
		isHomeScreen = windows.isHomeScreen()
	)

	env.lastResponse
}


//private val deviceModel: String by lazy {
//		Log.d(DeviceConstants.uiaDaemon_logcatTag, "getDeviceModel()")
//		val model = Build.MODEL
//		val manufacturer = Build.MANUFACTURER
//		val fullModelName = "$manufacturer-$model/$api"
//		Log.d(DeviceConstants.uiaDaemon_logcatTag, "Device model: $fullModelName")
//		fullModelName
//	}

private fun UiDevice.verifyCoordinate(x:Int,y:Int){
	assert(x in 0 until displayWidth) { "Error on click coordinate invalid x:$x - DisplayWith: $displayWidth" }
	assert(y in 0 until displayHeight) { "Error on click coordinate invalid y:$y - DisplayHeight: $displayHeight" }
}

private typealias twoPointStepableAction = (x0:Int,y0:Int,x1:Int,y1:Int)->Boolean
private fun UiDevice.twoPointAction(start: Pair<Int,Int>, end: Pair<Int,Int>, action: twoPointStepableAction):Boolean{
	val (x0,y0) = start
	val (x1,y1) = end
	verifyCoordinate(x0,y0)
	verifyCoordinate(x1,y1)
	return action(x0, y0, x1, y1)
}

private suspend fun UiDevice.minimizeMaximize(){
	val currentPackage = currentPackageName
	Log.d(logTag, "Original package name $currentPackage")

	pressRecentApps()
	// Cannot use wait for changes because it crashes UIAutomator
	delay(200) // avoid idle 0 which get the wait stuck for multiple seconds
//	measureTimeMillis { waitForIdle(idleTimeout) }.let { Log.d(logTag, "waited $it millis for IDLE") }

	for (i in (0 until 9)) {
		pressRecentApps()

		// Cannot use wait for changes because it waits some interact-able element
		delay(200) // avoid idle 0 which get the wait stuck for multiple seconds
//		measureTimeMillis { waitForIdle(idleTimeout) }.let { Log.d(logTag, "waited $it millis for IDLE") }

		Log.d(logTag, "Current package name $currentPackageName")
		if (currentPackageName == currentPackage)
			break
	}
}

private suspend fun UiDevice.launchApp(appPackageName: String, env: UiAutomationEnvironment, launchActivityDelay: Long, waitTime: Long): Boolean {
	var success = false
	// Launch the app
	val intent = env.context.packageManager
			.getLaunchIntentForPackage(appPackageName)

	// Clear out any previous instances
	intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)

	// Update environment
	env.launchedMainActivity = try {
		intent?.component?.className ?: ""
	} catch (e: IllegalStateException) {
		""
	}
	debugOut("determined launch-able main activity for pkg=${env.launchedMainActivity}",debugFetch)

	measureTimeMillis {

		env.context.startActivity(intent)

		// Wait for the app to appear
		wait(Until.hasObject(By.pkg(appPackageName).depth(0)),
				waitTime)

		delay(launchActivityDelay)
		success = UiHierarchy.waitFor(env, waitTime, actableAppElem)
		// mute audio after app launch (for very annoying apps we may need a contentObserver listening on audio setting changes)
		val audio = env.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
		audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE,0)
		audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE,0)
		audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE,0)

	}.also { Log.d(logTag, "TIME: load-time $it millis") }
	return success
}

private fun UiDevice.rotate(rotation: Int,automation: UiAutomation):Boolean{
	val currRotation = (displayRotation * 90)
	Log.d(logTag, "Current rotation $currRotation")
	// Android supports the following rotations:
	// ROTATION_0 = 0;
	// ROTATION_90 = 1;
	// ROTATION_180 = 2;
	// ROTATION_270 = 3;
	// Thus, instead of 0-360 we have 0-3
	// The rotation calculations is: [(current rotation in degrees + rotation) / 90] % 4
	// Ex: curr = 90, rotation = 180 => [(90 + 360) / 90] % 4 => 1
	val newRotation = ((currRotation + rotation) / 90) % 4
	Log.d(logTag, "New rotation $newRotation")
	unfreezeRotation()
	return automation.setRotation(newRotation)
}

private fun UiDevice.sendIntent(action: String, category: String, uriString: String, activityName: String, appPackageName: String, env: UiAutomationEnvironment): Boolean{
	val context = env.context
	val intent = Intent()
	val intentAction = if (action.isBlank())
		Intent.ACTION_DEFAULT
	else
		action

	val intentCategory = if (category.isBlank())
		Intent.CATEGORY_DEFAULT
	else
		category
	intent.setAction(intentAction)
	intent.addCategory(intentCategory)
	if (uriString.startsWith("file://")) {
		val fileName = uriString.substring("file://".length)
		val openfile = File("/sdcard/$fileName")
		if (!openfile.exists()) {
			Log.d("SendIntent","${openfile} does not exist")
			return false
		}
		val fileUri = FileProvider.getUriForFile(context, context.getApplicationContext().getPackageName(), openfile);
		intent.setData(fileUri)
		Log.d("SendIntent",fileUri.toString())
	} else
	{
		intent.setData(Uri.parse(uriString))
	}

	intent.setClassName(appPackageName,activityName)
	intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	intent.addFlags(Intent.FLAG_FROM_BACKGROUND)
	intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	//intent.setComponent(ComponentName(appPackageName,activityName))
	context.startActivity(intent)
//	val pm = context.packageManager
//	val handlers = pm.queryIntentActivities(intent,PackageManager.GET_RESOLVED_FILTER)
//	if (handlers.size>0)
//	{
//		val targethandler = handlers.find {resolveFilter ->
//			resolveFilter.activityInfo.packageName.equals(appPackageName) &&
//					resolveFilter.activityInfo.name.equals(activityName)
//		}
//		if (targethandler != null)
//		{
//			intent.setClassName(appPackageName,activityName)
//			context.startActivity(intent)
//		}
//	}
	return true
}
