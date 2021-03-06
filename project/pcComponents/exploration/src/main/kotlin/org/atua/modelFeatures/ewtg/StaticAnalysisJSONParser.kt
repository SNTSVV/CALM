/*
 * ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
 * Copyright (C) 2019 - 2021 University of Luxembourg
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.atua.modelFeatures

import org.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ewtg.EWTG
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.DialogType
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.inputRepo.deviceEnvironment.DeviceEnvironmentConfigurationFileHelper
import org.atua.modelFeatures.inputRepo.intent.IntentData
import org.atua.modelFeatures.inputRepo.intent.IntentFilter
import org.atua.modelFeatures.inputRepo.textInput.InputConfigurationFileHelper
import org.atua.modelFeatures.inputRepo.textInput.TextInput
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

class StaticAnalysisJSONParser() {
    companion object {
        fun readAppModel(
            appModelFile: Path,
            atuaMF: org.atua.modelFeatures.ATUAMF,
            manualIntent: Boolean,
            manualInput: Boolean
        ) {
            if (appModelFile != null) {
                //val activityEventList = List<ActivityEvent>()
                val jsonData = String(Files.readAllBytes(appModelFile))
                val jObj = JSONObject(jsonData)
                readDialogs(jObj)
                org.atua.modelFeatures.ATUAMF.log.debug("Reading Window Transition Graph")
                atuaMF.wtg.constructFromJson(jObj)
                atuaMF.wtg.edges(Launcher.getOrCreateNode()).forEach {
                    val destWindow = it.label.destination
                    if (!WindowManager.instance.launchActivities.contains(destWindow))
                        WindowManager.instance.launchActivities.add(destWindow)
                }
                readActivityAlias(jObj, atuaMF.activityAlias)
                readWindowWidgets(
                    jObj, atuaMF.allEventHandlers,
                    atuaMF.wtg, atuaMF.statementMF!!
                )
                WindowManager.instance.updatedModelWindows.forEach { w ->
                    if (w is OptionsMenu) {
                        val ownerActivity =
                            WindowManager.instance.updatedModelWindows.find { it is Activity && it.classType == w.classType }
                        w.ownerActivity = ownerActivity
                    }
/*                    if (w is Dialog) {
                        val ownerActivity = WindowManager.instance.allWindows.find { it is Activity && it.activityClass == w.activityClass }
                        w.ownerActivity = ownerActivity
                    }*/
                }
                readMenuItemTexts(jObj)
                readActivityDialogs(jObj, atuaMF.allDialogOwners)
                readWindowHandlers(jObj, atuaMF.windowHandlersHashMap, atuaMF.wtg, atuaMF.statementMF!!)
                org.atua.modelFeatures.ATUAMF.log.debug("Reading modified method invocation")
                readModifiedMethodTopCallers(
                    jObj,
                    atuaMF.modifiedMethodWithTopCallers,
                    atuaMF.statementMF!!,
                    atuaMF.allTargetHandlers,
                    atuaMF.windowHandlersHashMap,
                    atuaMF.modifiedMethodsByWindow,
                    atuaMF.untriggeredTargetHiddenHandlers
                )
                readModifiedMethodInvocation(
                    jObj, atuaMF.wtg, atuaMF.notFullyExercisedTargetInputs, atuaMF.allTargetStaticWidgets,
                    atuaMF.statementMF!!, atuaMF.modifiedMethodsByWindow, atuaMF.targetItemEvents
                )
                readUnreachableModfiedMethods(jObj, atuaMF.unreachableModifiedMethods)
                //log.debug("Reading all strings")
                //readAllStrings(jObj)
                readEventCorrelation(jObj, atuaMF.inputWindowCorrelation, atuaMF.wtg)
                readMethodDependency(jObj, atuaMF.methodTermsHashMap, atuaMF.statementMF!!)
                readWindowDependency(jObj, atuaMF.windowTermsHashMap, atuaMF.wtg)
                if (manualIntent) {
                    val intentModelFile = atuaMF.getIntentModelFile()
                    if (intentModelFile != null) {
                        readIntentModel(
                            atuaMF.intentFilters, atuaMF.getAppName(),
                            atuaMF.wtg, atuaMF.notFullyExercisedTargetInputs, atuaMF.targetIntFilters, intentModelFile
                        )
                    }
                }
                if (manualInput) {
                    val textInputFile = atuaMF.getTextInputFile()
                    if (textInputFile != null) {
                        atuaMF.inputConfiguration =
                            InputConfigurationFileHelper.readInputConfigurationFile(textInputFile)
                        TextInput.inputConfiguration = atuaMF.inputConfiguration

                    }
                }
                val deviceConfigurationFile = atuaMF.getDeviceConfigurationFile()
                if (deviceConfigurationFile != null) {
                    atuaMF.deviceEnvironmentConfiguration =
                        DeviceEnvironmentConfigurationFileHelper.readInputConfigurationFile(deviceConfigurationFile)
                }
            }

        }

        private fun readDialogs(jObj: JSONObject) {
            val jsonDialogClasses = jObj.getJSONObject("allDialogs")
            if (jsonDialogClasses != null) {
                jsonDialogClasses.keys().asSequence().forEach { dialogTypeString ->
                    val dialogType = when (dialogTypeString) {
                        "libraryDialogs" -> DialogType.LIBRARY_DIALOG
                        "applicationDialogs" -> DialogType.APPLICATION_DIALOG
                        "dialogFragments" -> DialogType.DIALOG_FRAGMENT
                        else -> DialogType.UNKNOWN
                    }
                    WindowManager.instance.dialogClasses.put(dialogType, ArrayList())
                    val dialogClasses = jsonDialogClasses.get(dialogTypeString) as JSONArray
                    WindowManager.instance.dialogClasses.get(dialogType)!!.addAll(dialogClasses.map { it.toString() })
                    dialogClasses.forEach { it ->
                        Dialog.getOrCreateNode(
                            nodeId = Dialog.getNodeId(),
                            classType = it.toString(),
                            allocMethod = "",
                            runtimeCreated = false,
                            isBaseModel = false
                        )
                    }

                }
            }
        }

        private fun readActivityAlias(jObj: JSONObject, activityAlias: HashMap<String, String>) {
            val jsonActivityAlias = jObj.getJSONObject("activityAlias")
            if (jsonActivityAlias != null) {
                jsonActivityAlias.keys().asSequence().forEach { alias ->
                    val activity = jsonActivityAlias.get(alias).toString()
                    activityAlias.put(alias, activity)
                }
            }
        }

        private fun readWindowDependency(
            jObj: JSONObject,
            windowTermsHashMap: HashMap<Window, HashMap<String, Long>>,
            wtg: EWTG
        ) {
            val jsonWindowTerm = jObj.getJSONObject("windowsDependency")
            if (jsonWindowTerm != null) {
                windowTermsHashMap.putAll(readWindowTerms(jsonWindowTerm, wtg))
            }
        }

        private fun readWindowHandlers(
            jObj: JSONObject, windowHandlersHashMap: HashMap<Window, Set<String>>,
            wtg: EWTG, statementCoverageMF: StatementCoverageMF
        ) {
            val jsonWindowHandlers = jObj.getJSONObject("windowHandlers")
            if (jsonWindowHandlers != null) {
                windowHandlersHashMap.putAll(readWindowHandlers(jsonWindowHandlers, wtg, statementCoverageMF))
            }
        }

        private fun readMethodDependency(
            jObj: JSONObject,
            methodTermsHashMap: HashMap<String, HashMap<String, Long>>,
            statementMF: StatementCoverageMF
        ) {
            var jsonMethodDepedency = jObj.getJSONObject("methodDependency")
            if (jsonMethodDepedency != null) {
                methodTermsHashMap.putAll(StaticAnalysisJSONParser.readMethodTerms(jsonMethodDepedency, statementMF))
            }
        }

        private fun readEventCorrelation(
            jObj: JSONObject,
            inputWindowCorrelation: HashMap<Input, HashMap<Window, Double>>,
            wtg: EWTG
        ) {
            var eventCorrelationJson = jObj.getJSONObject("event_window_Correlation")
            if (eventCorrelationJson != null) {
                inputWindowCorrelation.putAll(readEventWindowCorrelation(eventCorrelationJson, wtg))
            }
        }

        private fun readIntentModel(
            intentFilters: HashMap<String, ArrayList<IntentFilter>>, appName: String,
            wtg: EWTG,
            allTargetInputs: HashSet<Input>,
            targetIntFilters: HashMap<IntentFilter, Int>,
            intentModelFile: Path
        ) {
            if (intentModelFile != null) {
                val jsonData = String(Files.readAllBytes(intentModelFile))
                val jObj = JSONObject(jsonData)
                val activitiesJson = jObj.getJSONArray("activities")
                activitiesJson.forEach {
                    StaticAnalysisJSONParser.readActivityIntentFilter(it as JSONObject, intentFilters, appName)
                }
                intentFilters.forEach { t, u ->
                    val activityName = t
                    val qualifiedActivityName = activityName
                    var intentActivityNode =
                        WindowManager.instance.updatedModelWindows.find { it.classType == qualifiedActivityName }
                    if (intentActivityNode == null) {
                        intentActivityNode = Activity.getOrCreateNode(
                            Activity.getNodeId(), qualifiedActivityName, false, false
                        )
                    }
                    WindowManager.instance.intentFilter.put(intentActivityNode, u)
                    /*u.forEach {
                        for (meaningNode in WindowManager.instance.allMeaningWindows) {
                            val intentEvent = Input(eventType = EventType.callIntent,
                                    eventHandlers = HashSet(), widget = null,sourceWindow = meaningNode)
                            intentEvent.data = it
                            wtg.add(meaningNode, intentActivityNode!!, intentEvent)
                        }

                    }*/

                    if (allTargetInputs.filter {
                            it.sourceWindow.classType.contains(activityName)
                                    && (it.eventType == EventType.implicit_rotate_event
                                    || it.eventType == EventType.implicit_lifecycle_event
                                    || it.eventType == EventType.implicit_power_event)
                        }.isNotEmpty()) {
                        u.forEach {
                            targetIntFilters.put(it, 0)
                        }
                    }
                }
            }
        }


        private fun readMenuItemTexts(jObj: JSONObject) {
            var jMap = jObj.getJSONObject("menuItemTexts")
            readMenuItemText(
                jMap,
                WindowManager.instance.updatedModelWindows.filter { it is OptionsMenu } as List<OptionsMenu>)
        }

/*    private fun readAllStrings(jObj: JSONObject) {
        var jMap = jObj.getJSONArray("allStrings")
        StaticAnalysisJSONFileHelper.readAllStrings(jMap,generalDictionary)
    }*/

        private fun readUnreachableModfiedMethods(jObj: JSONObject, unreachableModifiedMethods: ArrayList<String>) {
            var jMap = jObj.getJSONArray("unreachableModifiedMethods")
            readUnreachableModifiedMethods(jsonArray = jMap, methodList = unreachableModifiedMethods)
        }

        private fun readWindowWidgets(
            jObj: JSONObject, allEventHandlers: HashSet<String>,
            wtg: EWTG, statementCoverageMF: StatementCoverageMF
        ) {
            var jMap1 = jObj.getJSONObject("allWindow_Widgets")
            readWindowWidgets(jMap1, wtg)
            var jMap2 = jObj.getJSONObject("allWidgetEvent")
            readAllWidgetEvents(jMap2, wtg, allEventHandlers, statementCoverageMF)

        }

        private fun readModifiedMethodTopCallers(
            jObj: JSONObject,
            modifiedMethodTopCallersMap: HashMap<String, Set<String>>,
            statementCoverageMF: StatementCoverageMF,
            allTargetHandlers: HashSet<String>,
            windowHandlersHashMap: HashMap<Window, Set<String>>,
            allTargetWindow_ModifiedMethods: HashMap<Window, HashSet<String>>,
            untriggeredTargetHandlers: HashSet<String>
        ) {
            var jMap = jObj.getJSONObject("modiMethodTopCaller")
            readModifiedMethodTopCallers(jMap, modifiedMethodTopCallersMap, statementCoverageMF, windowHandlersHashMap)
            //Add windows containing top caller to allTargetWindows list
            modifiedMethodTopCallersMap.forEach { modMethod, topCallers ->
                allTargetHandlers.addAll(topCallers)
                topCallers.forEach { caller ->
                    val windows = windowHandlersHashMap.filter { it.value.contains(caller) }.map { it.key }
                    if (windows.isNotEmpty()) {
                        windows.forEach { w ->
                            if (!allTargetWindow_ModifiedMethods.contains(w)) {
                                allTargetWindow_ModifiedMethods.put(w, hashSetOf())
                            }
                            allTargetWindow_ModifiedMethods[w]!!.add(modMethod)
                        }
                    }
                }
            }

            untriggeredTargetHandlers.addAll(allTargetHandlers)
        }

        private fun readModifiedMethodInvocation(
            jObj: JSONObject,
            wtg: EWTG,
            allTargetInputs: HashSet<Input>,
            allTargetStaticWidgets: HashSet<EWTGWidget>,
            statementCoverageMF: StatementCoverageMF,
            allTargetWindow_ModifiedMethods: HashMap<Window, HashSet<String>>,
            targetItemEvents: HashMap<Input, HashMap<String, Int>>
        ) {
            var jMap = jObj.getJSONObject("modiMethodInvocation")
            readModifiedMethodInvocation(
                jsonObj = jMap,
                wtg = wtg,
                allTargetInputs = allTargetInputs,
                allTargetEWTGWidgets = allTargetStaticWidgets,
                statementCoverageMF = statementCoverageMF
            )
            allTargetInputs.forEach {
                val sourceWindow = it.sourceWindow
                if (!allTargetWindow_ModifiedMethods.contains(sourceWindow) && sourceWindow !is OutOfApp) {
                    allTargetWindow_ModifiedMethods.put(sourceWindow, hashSetOf())
                }
                allTargetWindow_ModifiedMethods[sourceWindow]!!.addAll(it.modifiedMethods.keys)
            }

            allTargetInputs.filter {
                listOf<EventType>(
                    EventType.item_click, EventType.item_long_click,
                    EventType.item_selected
                ).contains(it.eventType)
            }.forEach {
                val eventInfo = HashMap<String, Int>()
                targetItemEvents.put(it, eventInfo)
                eventInfo["max"] = 3
                eventInfo["count"] = 0
            }

        }


        fun readActivityDialogs(jObj: JSONObject, allDialogOwners: HashMap<String, ArrayList<String>>) {
            val jMap = jObj.getJSONObject("allActivityDialogs")
            //for each Activity transition
            jMap.keys().asSequence().forEach { key ->
                val activity = key as String
                if (!allDialogOwners.containsKey(activity)) {
                    allDialogOwners[activity] = ArrayList()
                }
                val dialogs = jMap[key] as JSONArray
                dialogs.forEach {
                    allDialogOwners[activity]!!.add(it as String)
                }
            }
        }

        fun readActivityOptionMenuItem(
            jObj: JSONObject,
            allActivityOptionMenuItems: HashMap<String, ArrayList<String>>
        ) {
            val jMap = jObj.getJSONObject("allActivityOptionMenuItems")
            //for each Activity transition
            jMap.keys().asSequence().forEach { key ->
                val activity = key as String
                if (!allActivityOptionMenuItems.contains(activity)) {
                    allActivityOptionMenuItems[activity] = ArrayList()
                }
//            val menuItemsJson = jMap[key] as JSONArray
//            menuItemsJson.forEach {
//                val widgetInfo = StaticAnalysisJSONFileHelper.widgetParser(it as String)
//                val menuItemWidget = StaticWidget.getOrCreateStaticWidget(widgetInfo["id"]!!,"android.view.menu",false)
//                allActivityOptionMenuItems[activity]!!.add(menuItemWidget)
//            }

            }
        }


        fun widgetParser(widgetInfo: String): HashMap<String, String> {
            //widgetInfo = "INFL[android.widget.ListView,WID[2131427361|PlaylistsListView]464,7251]7252"
            try {
                val result = HashMap<String, String>()
                var temp: String = widgetInfo
                //get node type
                val nodetypeIdx = widgetInfo.indexOf("[")
                val nodeType = widgetInfo.substring(0, nodetypeIdx)
                temp = temp.substring(nodetypeIdx + 1)
                //get class type
                val classtypeIdx = temp.indexOf(",")
                if (classtypeIdx < 0)
                    return result
                val classType = temp.substring(0, classtypeIdx)
                result["className"] = classType
                temp = temp.substring(classtypeIdx + 1)
                //get idNode
                val idnodeIdx = temp.indexOf("WID[")
                if (idnodeIdx != -1) {
                    temp = temp.substring(idnodeIdx + 1)
                    //get resourceid
                    val resourceidIdx = temp.indexOf("|")
                    val resourceId = temp.substring(0, resourceidIdx)
                    result["resourceId"] = resourceId
                    temp = temp.substring(resourceidIdx + 1)
                    //get resourceidName
                    val resourceidnameIdx = temp.indexOf("]")
                    val resourceIdName = temp.substring(0, resourceidnameIdx)
                    result["resourceIdName"] = resourceIdName
                    temp = temp.substring(resourceidnameIdx + 1)
                }
                //get sootandroidId
                val endNodeIdIdx = temp.lastIndexOf("]")
                val sootandroidId = temp.substring(endNodeIdIdx + 1)
                result["id"] = sootandroidId
                return result
            } catch (e: Exception) {
                throw e
            }
        }

        fun windowParser(windowInfo: String): HashMap<String, String> {
            //"DIALOG[com.teleca.jamendo.dialog.LyricsDialog]2686, alloc: <com.teleca.jamendo.activity.PlayerActivity: void lyricsOnClick(android.view.View)>"
            //"ACT[com.teleca.jamendo.activity.PlayerActivity]823"
            val result = HashMap<String, String>()
            var temp: String = windowInfo
            //result["id"] = temp.substring(temp.lastIndexOf("]")+1);
            //get node type
            val nodetypeIdx = windowInfo.indexOf("[")
            val nodeType = windowInfo.substring(0, nodetypeIdx)
            result["NodeType"] = nodeType
            temp = temp.substring(nodetypeIdx + 1)
            //get class type
            val classtypeEndIdx = temp.lastIndexOf("]")
            val classType = temp.substring(0, classtypeEndIdx)
            result["className"] = classType
            temp = temp.substring(classtypeEndIdx + 1)
            //get sootandroidId
            val allocIndex = temp.indexOf(", alloc: ")
            if (allocIndex == -1) {
                val sootandroidId = temp
                result["id"] = sootandroidId
            } else {
                val sootandroidId = temp.substring(0, allocIndex)
                result["id"] = sootandroidId
                temp = temp.substring(allocIndex + ", alloc: ".length)
                result["allocMethod"] = temp
            }
            return result
        }

        val widgetByWidgetString = HashMap<Window, HashMap<String, EWTGWidget>>()
        val windowByWindowString = HashMap<String, Window>()

        fun readWindowWidgets(
            jsonObj: JSONObject,
            wtg: EWTG
        ) {
            jsonObj.keys().asSequence().forEach { key ->
                val windowJson = key as String
                val window = getParsedWindowOrCreateNewOne(windowJson, wtg)
                val widgetListJson = jsonObj[key] as JSONObject
                widgetListJson.keys().asSequence().forEach {
                    if (widgetListJson[it]!! is JSONObject) {
                        val jsonObjectRoot = widgetListJson[it] as JSONObject
                        val widgetInfoJson = jsonObjectRoot["widget"].toString()
                        val parent = getParsedEWTGWidgetOrCreateNewOne(window, widgetInfoJson)
                        if (parent != null) {
                            val jsonChildren = jsonObjectRoot["children"] as JSONObject
                            parseWindowWigetsChildren(jsonChildren, parent)
                        }
                    } else {
                        val widgetInfoJson = widgetListJson[it].toString()
                        getParsedEWTGWidgetOrCreateNewOne(window, widgetInfoJson)
                    }
                }
            }
        }

        public fun getParsedWindowOrCreateNewOne(
            windowJson: String,
            wtg: EWTG
        ): Window {
            val window = if (windowByWindowString.containsKey(windowJson)) {
                windowByWindowString.get(windowJson)!!
            } else {
                val windowInfo = windowParser(windowJson)
                val wtgNode = wtg.getOrCreateWTGNode(windowInfo)
                windowByWindowString.putIfAbsent(windowJson, wtgNode)
                widgetByWidgetString.putIfAbsent(wtgNode, HashMap())
                wtgNode
            }
            return window
        }

        public fun getParsedEWTGWidgetOrCreateNewOne(
            window: Window,
            widgetInfoJson: String
        ): EWTGWidget? {
            val parent = if (widgetByWidgetString.get(window)!!.containsKey(widgetInfoJson)) {
                widgetByWidgetString[window]!!.get(widgetInfoJson)!!
            } else {
                val widgetInfo = widgetParser(widgetInfoJson)
                if (!widgetInfo.containsKey("id"))
                    return null
                val newWidget = EWTGWidget.getOrCreateStaticWidget(
                    widgetId = widgetInfo["id"]!!,
                    resourceId = widgetInfo["resourceId"] ?: "",
                    resourceIdName = widgetInfo["resourceIdName"] ?: "",
                    className = widgetInfo["className"]!!,
                    window = window
                )
                widgetByWidgetString[window]!!.put(widgetInfoJson, newWidget)
                newWidget
            }
            return parent
        }

        private fun parseWindowWigetsChildren(jsonChildren: JSONObject, parent: EWTGWidget) {
            jsonChildren.keys().asSequence().forEach {
                if (jsonChildren[it]!! is JSONObject) {
                    val jsonObjectTree = jsonChildren[it] as JSONObject
                    val widgetInfoJson = jsonObjectTree["widget"].toString()
                    val widget = getParsedEWTGWidgetOrCreateNewOne(parent.window, widgetInfoJson)
                    if (widget != null) {
                        widget.parent = parent
                        val jsonChildren = jsonObjectTree["children"] as JSONObject
                        parseWindowWigetsChildren(jsonChildren, widget)
                    }

                } else {
                    val widgetInfoJson = jsonChildren[it].toString()
                    getParsedEWTGWidgetOrCreateNewOne(parent.window, widgetInfoJson)
                }
            }
        }

        fun readAllWidgetEvents(
            jsonObj: JSONObject, wtg: EWTG, allEventHandlers: HashSet<String>, statementCoverageMF: StatementCoverageMF
        ) {
            jsonObj.keys().asSequence().forEach { key ->
                val windowJson = key as String
                val window = getParsedWindowOrCreateNewOne(windowJson, wtg)
                val widgetListJson = jsonObj[key] as JSONObject
                widgetListJson.keys().asSequence().forEach {
                    val widgetInfoJson = it
                    var ewtgWidget: EWTGWidget? = null
                    val eventListJsons = widgetListJson[it]!! as JSONArray
                    eventListJsons.forEach { eventJson ->
                        val eventJsonObject = eventJson as JSONObject
                        val jsonEventHandlers = eventJsonObject["handler"] as JSONArray
                        val eventHandlers =
                            jsonEventHandlers.map { statementCoverageMF.getMethodId(it as String) }.toSet()
                        val jsonEventType = eventJsonObject["action"] as String
                        if (!Input.isIgnoreEvent(jsonEventType) ) {
                            if (Input.isNoWidgetEvent(jsonEventType)) {
                                ewtgWidget = null
                            } else {
                                try {
                                    ewtgWidget = getParsedEWTGWidgetOrCreateNewOne(window, widgetInfoJson)
                                } catch (e: Exception) {
                                    ewtgWidget = null
                                }

                            }
                            if (Input.isNoWidgetEvent(jsonEventType) ||
                                (!Input.isNoWidgetEvent(jsonEventType) &&
                                        ewtgWidget != null)
                            ) {
                                val event = getOrCreateTargetEvent(
                                    eventHandlers = jsonEventHandlers.map { statementCoverageMF.getMethodId(it as String) }
                                        .toSet(),
                                    eventTypeString = jsonEventType,
                                    widget = ewtgWidget,
                                    sourceWindow = window,
                                    allTargetInputs = HashSet()
                                )
                                if (event != null)
                                    allEventHandlers.addAll(event.eventHandlers)

                                if (ewtgWidget != null && ewtgWidget!!.className.contains("Layout")) {
                                    var createItemClick = false
                                    var createItemLongClick = false
                                    /*when (jsonEventType) {
                                       "touch" -> {
                                            createItemClick=true
                                        createItemLongClick=true
                                        }
                                        "click" -> {
                                            createItemClick=true
                                        }
                                        "long_click" -> {
                                            createItemLongClick=true
                                        }
                                    }*/
                                    //create item click and long click
                                    if (createItemClick) {
                                        val itemClick = getOrCreateTargetEvent(
                                            eventHandlers = jsonEventHandlers.map { statementCoverageMF.getMethodId(it as String) }
                                                .toSet(),
                                            eventTypeString = "item_click",
                                            widget = ewtgWidget,
                                            sourceWindow = window,
                                            allTargetInputs = HashSet()
                                        )
                                        if (itemClick != null)
                                            allEventHandlers.addAll(itemClick.eventHandlers)
                                    }

                                    if (createItemLongClick) {
                                        //create item click and long click
                                        val itemLongClick = getOrCreateTargetEvent(
                                            eventHandlers = jsonEventHandlers.map { statementCoverageMF.getMethodId(it as String) }
                                                .toSet(),
                                            eventTypeString = "item_long_click",
                                            widget = ewtgWidget,
                                            sourceWindow = window,
                                            allTargetInputs = HashSet()
                                        )
                                        if (itemLongClick != null)
                                            allEventHandlers.addAll(itemLongClick.eventHandlers)
                                    }
                                }
                            }

                        }

                    }
                }
            }
        }

        fun readModifiedMethodInvocation(
            jsonObj: JSONObject,
            wtg: EWTG,
            allTargetEWTGWidgets: HashSet<EWTGWidget>,
            allTargetInputs: HashSet<Input>,
            statementCoverageMF: StatementCoverageMF
        ) {

            jsonObj.keys().asSequence().forEach { key ->
                val source = key as String
                val sourceNode = getParsedWindowOrCreateNewOne(source, wtg)
                val methodInvocations = jsonObj[key] as JSONObject
                methodInvocations.keys().asSequence().forEach { widgetJson ->
                    val widgetInvocation_json = methodInvocations[widgetJson] as JSONArray
                    //val widget_methodInvocations = Widget_MethodInvocations(staticWidget, ArrayList())

                    //widgets_modMethodInvocation[staticWidget.widgetId] = widget_methodInvocations
                    widgetInvocation_json.forEach {
                        val jsonEvent = it as JSONObject
                        val jsonEventType: String
                        if ((jsonEvent["eventType"] as String) == "touch")
                            jsonEventType = "click"
                        else
                            jsonEventType = jsonEvent["eventType"] as String
                        var ewtgWidget: EWTGWidget?
                        if (!Input.isIgnoreEvent(jsonEventType)) {
                            if (Input.isNoWidgetEvent(jsonEventType)
                            ) {
                                ewtgWidget = null
                            } else {
                                try {
                                    ewtgWidget = getParsedEWTGWidgetOrCreateNewOne(sourceNode, widgetJson)
                                    if (ewtgWidget != null) {
                                        if (!allTargetEWTGWidgets.contains(ewtgWidget)) {
                                            allTargetEWTGWidgets.add(ewtgWidget)
                                        }
                                    }
                                } catch (e: Exception) {
                                    ewtgWidget = null
                                }

                            }
                            if (Input.isNoWidgetEvent(jsonEventType) ||
                                (!Input.isNoWidgetEvent(jsonEventType) &&
                                        ewtgWidget != null)
                            ) {
                                val jsonEventHandler = jsonEvent["eventHandlers"] as JSONArray
                                val eventHandlers =
                                    jsonEventHandler.map { statementCoverageMF.getMethodId(it as String) }.toSet()
                                if (eventHandlers.isNotEmpty()) {
                                    val event = getOrCreateTargetEvent(
                                        eventHandlers = eventHandlers,
                                        eventTypeString = jsonEventType,
                                        widget = ewtgWidget,
                                        sourceWindow = sourceNode,
                                        allTargetInputs = allTargetInputs
                                    )
                                    if (event != null) {
                                        val methods = jsonEvent["modMethods"] as JSONArray
                                        val methodIds = methods.map { statementCoverageMF.getMethodId(it as String) }.filter { it.isNotBlank() }
                                        val methodStatements =
                                            methodIds.map { statementCoverageMF.getMethodStatements(it) }.flatten()
                                        event.modifiedMethods.putAll(methodIds.associateWith { false })
                                        event.modifiedMethodStatement.putAll(methodStatements.associateWith { false })
                                    }
                                }

                            }

                        }

                    }

                }
            }
        }

        fun readModifiedMethodTopCallers(
            jsonObj: JSONObject,
            methodTopCallersMap: HashMap<String, Set<String>>,
            statementCoverageMF: StatementCoverageMF,
            windowHandlersHashMap: HashMap<Window, Set<String>>
        ) {
            val allHiddenHandlers = windowHandlersHashMap.values.flatten().distinct()
            jsonObj.keys().asSequence().forEach {
                val methodId = statementCoverageMF.getMethodId(it)
                val methodTopCallers = HashSet<String>()
                val methodTopCallersJsonArray = jsonObj[it] as JSONArray
                methodTopCallersJsonArray.asSequence().forEach {
                    val callId = statementCoverageMF.getMethodId(it.toString())
                    if (callId.isNotBlank() && allHiddenHandlers.contains(callId))
                        methodTopCallers.add(callId)
                }
                methodTopCallersMap.put(methodId, methodTopCallers)
            }


        }

        fun getOrCreateTargetEvent(
            eventHandlers: Set<String>,
            eventTypeString: String,
            widget: EWTGWidget?,
            sourceWindow: Window,
            allTargetInputs: HashSet<Input>
        ): Input? {
            val event = Input.getOrCreateInput(
                eventHandlers = eventHandlers,
                eventTypeString = eventTypeString,
                widget = widget,
                sourceWindow = sourceWindow,
                createdAtRuntime = false,
                modelVersion = ModelVersion.RUNNING
            )
            if (event != null)
                allTargetInputs.add(event)
            return event
        }

        fun readUnreachableModifiedMethods(jsonArray: JSONArray?, methodList: ArrayList<String>) {
            if (jsonArray == null) {
                throw Exception("No never used modified methods object")
            }
            jsonArray.forEach { key ->
                val methodSig = key
                methodList.add(methodSig.toString())
            }
        }

        fun readMenuItemText(jsonObj: JSONObject, optionsMenuList: List<OptionsMenu>) {
            jsonObj.keys().asSequence().forEach { key ->
                val widgetInfo = widgetParser(key)
                optionsMenuList.forEach {
                    val menuItem = it.widgets.find { it.widgetId == widgetInfo["id"]!! }
                    if (menuItem != null) {
                        menuItem.possibleTexts.addAll(ArrayList((jsonObj[key] as JSONArray).map { it.toString() }))
                    }
                }
            }
        }

        fun readAllStrings(jsonArray: JSONArray, dictionary: ArrayList<String>) {
            jsonArray.forEach {
                val s = it.toString()
                if (!dictionary.contains(s)) {
                    dictionary.add(s)
                }
            }
        }

        //Read intent-filter
        fun readActivityIntentFilter(
            jsonObj: JSONObject,
            activityIntentFilters: HashMap<String, ArrayList<IntentFilter>>,
            appPackageName: String
        ) {
            val intentActivity = jsonObj.getString("name")
            val activityName = if (intentActivity.startsWith(".")) {
                appPackageName + intentActivity
            } else {
                intentActivity
            }
            if (!activityIntentFilters.containsKey(activityName)) {
                activityIntentFilters.put(activityName, ArrayList())
            }
            (jsonObj.get("intent-filters") as JSONArray).forEach {
                val intentFilter = readIntentFilter(activityName, it as JSONObject)
                activityIntentFilters[activityName]!!.add(intentFilter)
            }
        }

        fun readIntentFilter(activityNaME: String, intentJson: JSONObject): IntentFilter {
            val intentFilter = IntentFilter(activityNaME)
            intentJson.keys().asSequence().forEach { key ->
                if (key == "actions") {
                    readIntentFilterActions(intentJson[key] as JSONArray).forEach {
                        intentFilter.addAction(it)
                    }
                }
                if (key == "categories") {
                    readIntentFilterCategories(intentJson[key] as JSONArray).forEach {
                        intentFilter.addCategory(it)
                    }
                }
                if (key == "data") {
                    readIntentFilterDataList(intentJson[key] as JSONArray).forEach {
                        intentFilter.addData(it)
                    }
                }
            }
            return intentFilter
        }

        fun readIntentFilterActions(actionsJson: JSONArray): List<String> {
            val actions = ArrayList<String>()
            actionsJson.forEach {
                actions.add(it.toString())
            }
            return actions
        }

        fun readIntentFilterCategories(categoriesJson: JSONArray): List<String> {
            val categories = ArrayList<String>()
            categoriesJson.forEach {
                categories.add(it.toString())
            }
            return categories
        }

        fun readIntentFilterDataList(dataListJson: JSONArray): List<IntentData> {
            val intentDataList = ArrayList<IntentData>()
            dataListJson.forEach {
                val intentData = readIntentFilterData(it as JSONObject)
                intentDataList.add(intentData)
            }
            return intentDataList
        }

        fun readIntentFilterData(dataJson: JSONObject): IntentData {
            var scheme = ""
            var host = ""
            var pathPrefix = ""
            val testData = ArrayList<String>()
            var mimeType = ""
            dataJson.keys().asSequence().forEach { key ->
                if (key == "scheme") {
                    scheme = dataJson[key].toString()
                }
                if (key == "host") {
                    host = dataJson[key].toString()
                }
                if (key == "pathPrefix") {
                    pathPrefix = dataJson[key].toString()
                }
                if (key == "mimeType") {
                    mimeType = dataJson[key].toString()
                }

                if (key == "testData") {
                    testData.addAll(readIntentFilterDataTestInstance(dataJson[key] as JSONArray))
                }
            }
            return IntentData(scheme = scheme, host = host, path = pathPrefix, mimeType = mimeType, testData = testData)
        }

        private fun readIntentFilterDataTestInstance(testDataJson: JSONArray): ArrayList<String> {
            val testData = ArrayList<String>()
            testDataJson.forEach {
                testData.add(it.toString())
            }
            return testData
        }

        fun readEventWindowCorrelation(jsonObj: JSONObject, wtg: EWTG): HashMap<Input, HashMap<Window, Double>> {
            val result: HashMap<Input, HashMap<Window, Double>> = HashMap()
            jsonObj.keys().asSequence().forEach { key ->
                val source = key as String
                val sourceInfo = windowParser(source)
                //val sourceId = sourceInfo["id"]!!
                val sourceNode = wtg.getOrCreateWTGNode(sourceInfo)
                val eventCorrelations = jsonObj[key] as JSONArray
                eventCorrelations.forEach { item ->
                    val jsonEventCorrelation = item as JSONObject
                    var eventType: String = jsonEventCorrelation["eventType"] as String
                    if (eventType == "touch")
                        eventType = "click"
                    var ewtgWidget: EWTGWidget? = null
                    if (!Input.isIgnoreEvent(eventType)) {
                        if (Input.isNoWidgetEvent(eventType)) {
                            ewtgWidget = null
                        } else {
                            try {
                                val jsonTargetWidget = jsonEventCorrelation["targetWidget"] as String
                                ewtgWidget = getParsedEWTGWidgetOrCreateNewOne(sourceNode, jsonTargetWidget)
                            } catch (e: Exception) {
                                ewtgWidget = null
                            }
                        }
                        val event = Input.getOrCreateInput(
                            eventHandlers = HashSet(),
                            eventTypeString = eventType,
                            sourceWindow = sourceNode,
                            widget = ewtgWidget,
                            createdAtRuntime = false,
                            modelVersion = ModelVersion.RUNNING
                        )
                        if (event != null) {
                            val correlation = HashMap<Window, Double>()
                            val jsonCorrelaions = jsonEventCorrelation["correlations"] as JSONArray
                            jsonCorrelaions.forEach { item ->
                                val jsonItem = item as JSONObject
                                val score = (jsonItem["second"] as String).toDouble()
                                val jsonWindow = jsonItem["first"] as String
                                val windowInfo = windowParser(jsonWindow)
                                val window = wtg.getOrCreateWTGNode(windowInfo)
                                correlation.put(window, score)
                            }
                            if (correlation.isNotEmpty())
                                result.put(event, correlation)
                        }
                    }

                }

            }
            return result
        }

        fun readMethodTerms(
            jsonObj: JSONObject,
            statementCoverageMF: StatementCoverageMF
        ): Map<String, HashMap<String, Long>> {
            val methodTerms = HashMap<String, HashMap<String, Long>>()
            jsonObj.keys().asSequence().forEach { methodSig ->
                val methodId = statementCoverageMF.getMethodId(methodSig)
                val terms = HashMap<String, Long>()
                methodTerms.put(methodId, terms)
                val jsonTerms = jsonObj[methodSig] as JSONObject
                jsonTerms.keys().asSequence().forEach { term ->
                    val count = jsonTerms[term] as Int
                    terms.put(term, count.toLong())
                }
            }
            return methodTerms
        }

        fun readWindowTerms(jsonObj: JSONObject, wtg: EWTG): Map<Window, HashMap<String, Long>> {
            val windowTerms = HashMap<Window, HashMap<String, Long>>()
            jsonObj.keys().asSequence().forEach { windowName ->
                val windowInfo = windowParser(windowName)
                val window = wtg.getOrCreateWTGNode(windowInfo)
                if (window != null) {
                    val terms = HashMap<String, Long>()
                    windowTerms.put(window, terms)
                    val jsonTerms = jsonObj[windowName] as JSONObject
                    jsonTerms.keys().asSequence().forEach { term ->
                        val count = jsonTerms[term] as Int
                        terms.put(term, count.toLong())
                    }
                }
            }
            return windowTerms
        }

        fun readWindowHandlers(
            jsonObj: JSONObject,
            wtg: EWTG,
            statementCoverageMF: StatementCoverageMF
        ): Map<Window, Set<String>> {
            val windowHandlers = HashMap<Window, HashSet<String>>()
            jsonObj.keys().asSequence().forEach { windowName ->
                val windowInfo = windowParser(windowName)
                val window = wtg.getOrCreateWTGNode(windowInfo)
                if (window != null) {
                    val handlers = HashSet<String>()
                    windowHandlers.put(window, handlers)
                    val jsonHandlers = jsonObj[windowName] as JSONArray
                    jsonHandlers.forEach {
                        val methodId = statementCoverageMF.getMethodId(it.toString())
                        handlers.add(methodId)
                    }
                }
            }
            return windowHandlers
        }
    }
}