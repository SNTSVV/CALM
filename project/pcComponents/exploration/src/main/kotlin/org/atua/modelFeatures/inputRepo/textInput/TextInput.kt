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
package org.atua.modelFeatures.inputRepo.textInput

import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.reducer.AbstractionFunction2
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.WindowManager
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.exploration.strategy.atua.task.InputCoverage
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.random.Random
import kotlin.streams.asSequence

class TextInput () {
    companion object{
        var inputConfiguration: InputConfiguration? = null
        var generalDictionary: HashSet<String> = HashSet()
        val historyTextInput: HashSet<String> = HashSet()
        val specificTextInput: HashMap<UUID, ArrayList<String>> = HashMap()

        protected var random = java.util.Random(Random.nextLong())
            private set

        fun getInputWidgetAssociatedDataField(widget: Widget, state: State<*>): DataField?{
            if(inputConfiguration !=null)
            {
                val dataField = inputConfiguration!!.getDataField(widget,state)
                return dataField
            }
            return null
        }

        fun getSetTextInputValue(widget: Widget, state: State<*>, randomInput: Boolean, inputCoverageType: InputCoverage): String {
            val inputValue = when (widget.inputType) {
                2 -> randomInt()
                1 -> inputString(widget, state,randomInput,inputCoverageType)
                else -> inputString(widget, state,randomInput,inputCoverageType)
            }
            return inputValue
        }

        fun resetInputData()
        {
            if (inputConfiguration !=null)
            {
                inputConfiguration!!.resetCurrentDataInputs()
            }
        }

        protected open fun inputString(widget: Widget, state: State<*>, randomInput: Boolean, inputCoverageType: InputCoverage): String{
            var inputCandidates = ""
            if(inputConfiguration !=null )
            {
                if ((randomInput && random.nextBoolean()) || !randomInput) {
                    inputCandidates = inputConfiguration!!.getInputDataField(widget, state)
                    if (inputCandidates.isNotBlank()) {
                        return inputCandidates
                    }
                }
            }
            if (widget.checked.isEnabled()) {
                when (inputCoverageType) {
                    InputCoverage.FILL_ALL -> return "true"
                    InputCoverage.FILL_EMPTY -> return "false"
                    InputCoverage.FILL_NONE -> return widget.checked.toString()
                    InputCoverage.FILL_RANDOM -> return if (random.nextBoolean())
                        "true"
                    else
                        "false"
                }
            }
            //widget is TextInput
            when (inputCoverageType) {
                InputCoverage.FILL_EMPTY -> return ""
                InputCoverage.FILL_NONE -> return widget.text
                InputCoverage.FILL_ALL, InputCoverage.FILL_RANDOM -> return generateInput(widget,state)
            }
            return ""
        }

        val specialCharactersTestedInputFields = HashSet<EWTGWidget>()
        val emptyStringTestedInputFields = HashSet<EWTGWidget>()

        private fun generateInput(widget: Widget, guiState: State<*>): String {
            val abstractState = AbstractStateManager.INSTANCE.getAbstractState(guiState)!!
            val window = abstractState.window
            val ewtgWidget = WindowManager.instance.guiWidgetEWTGWidgetMappingByWindow[window]?.get(widget)
            val reuseString = random.nextBoolean()
            if (reuseString && historyTextInput.isNotEmpty())
            {
                if (random.nextBoolean() && specificTextInput.containsKey(widget.uid))
                    return specificTextInput[widget.uid]!!.random()
                return historyTextInput.random()
            }
            val textChoices = ArrayList<Int>()
            textChoices.add(0)
            if (ewtgWidget!=null) {
                if (!emptyStringTestedInputFields.contains(ewtgWidget)) {
                    textChoices.add(1)
                }
                if (!specialCharactersTestedInputFields.contains(ewtgWidget))
                    textChoices.add(2)
            } else {
                textChoices.add(1)
                textChoices.add(2)
            }
            val textValue: String
            val choice = textChoices.random()
            when (choice) {
                0 -> textValue = generalDictionary.random()
                1 -> {
                    if (ewtgWidget!=null)
                        emptyStringTestedInputFields.add(ewtgWidget)
                    textValue = ""
                }
                else -> {
                    if (ewtgWidget!=null)
                        specialCharactersTestedInputFields.add(ewtgWidget)
                    val source = "1234567890abcdefghijklmnopqrstuvwxyz@#$%^&*()-_=+[]"
                    textValue = random.ints( random.nextInt(20).toLong()+3, 0, source.length)
                        .asSequence()
                        .map(source::get)
                        .joinToString("")
                }
            }
            historyTextInput.add(textValue)
            return textValue
        }

        protected open fun randomInt(): String{
            return random.nextInt().toString()
        }

        fun saveSpecificTextInputData(guiState: State<*>) {
            guiState.widgets.forEach {
                if (it.text.isNotBlank() && !arrayListOf<String>("ALLOW", "DENY", "DON'T ALLOW").contains(it.text)) {
                    generalDictionary.add(it.text)
                }
                if (it.isInputField && !it.text.isBlank() && !arrayListOf<String>("ALLOW", "DENY", "DON'T ALLOW").contains(it.text)) {
                    if (!specificTextInput.containsKey(it.uid)) {
                        specificTextInput.put(it.uid, ArrayList())
                    }
                    if (!specificTextInput[it.uid]!!.contains(it.text)) {
                        specificTextInput[it.uid]!!.add(it.text)
                    }
                }
            }
        }

    }
}