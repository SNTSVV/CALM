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

package org.atua.modelFeatures.ewtg

import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.DialogType
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.inputRepo.intent.IntentFilter
import org.droidmate.exploration.ExplorationContext
import org.droidmate.explorationModel.config.ModelConfig
import org.droidmate.explorationModel.interaction.Widget
import java.io.File
import java.nio.file.Files

class WindowManager {
    private constructor()
    val updatedModelWindows = ArrayList<Window>()
    val baseModelWindows = ArrayList<Window>()
    val userlikedWidgets = ArrayList<EWTGWidget>()
    val intentFilter = HashMap<Window, ArrayList<IntentFilter>>()
    val dialogClasses = HashMap<DialogType, ArrayList<String>>()
    val launchActivities = ArrayList<Window>()
    val allMeaningWindows
        get()= updatedModelWindows.filter { it !is FakeWindow && it !is Launcher && it !is OutOfApp &&
                !(it is Dialog && (it.isGrantedRuntimeDialog
                        || it.ownerActivitys.any{it is OutOfApp}))}

    val guiWidgetEWTGWidgetMappingByWindow = HashMap<Window, HashMap<Widget,EWTGWidget>>()
    fun dump(config: ModelConfig, atuaMF: org.atua.modelFeatures.ATUAMF,explorationContext: ExplorationContext<*,*,*>) {
        val wtgFolder = config.baseDir.resolve("EWTG")
        if (!Files.exists(wtgFolder))
            Files.createDirectory(wtgFolder)
        File(wtgFolder.resolve("EWTG_WindowList.csv").toUri()).bufferedWriter().use { all ->
            all.write(header())
            updatedModelWindows.forEach {
                all.newLine()
                all.write("${it.windowId};${it.getWindowType()};${it.classType};${it.isRuntimeCreated};${it.portraitDimension};${it.landscapeDimension};" +
                        "${it.portraitKeyboardDimension};${it.landscapeKeyboardDimension}")
            }
        }
        val widgetsFolder = wtgFolder.resolve("WindowsWidget")
        Files.createDirectory(widgetsFolder)
        updatedModelWindows.forEach {
            it.dumpStructure(widgetsFolder)
        }
        val eventsFolder = wtgFolder.resolve("WindowsEvents")
        Files.createDirectory(eventsFolder)
        updatedModelWindows.forEach {
            it.dumpEvents(eventsFolder,atuaMF,explorationContext)
        }
    }
    fun header(): String {
        return "[1]WindowID;[2]WindowType;[3]classType;[4]createdAtRuntime;[5]portraitDimension;[6]landscapeDimension;[7]portraitKeyboardDimension;[8]landscapeKeyboardDimension;"
    }

    fun concludeInputsEventHandlers() {
        updatedModelWindows.forEach {window ->
            window.inputs.forEach { input ->
                if (input.verifiedEventHandlers.isNotEmpty()) {
                    input.eventHandlers.clear()
                    input.eventHandlers.addAll(input.verifiedEventHandlers)
                }
            }
        }
    }

    companion object{
        val instance: WindowManager by lazy {
            WindowManager()
        }

    }
}