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
package org.droidmate.exploration.modelFeatures.atua.inputRepo.textInput

import kotlin.random.Random

open class DataField (val name: String,
                 val resourceIdPatterns: ArrayList<String> = ArrayList(),
                 val textHintPatterns: ArrayList<String> = ArrayList(),
                 val informationType: InformationType) {
    init {

    }
}

open class AutoGeneratedDataField(name: String,
                                  resourceIdPatterns: ArrayList<String> = ArrayList(),
                                  textHintPatterns: ArrayList<String> = ArrayList(),
                                  informationType: InformationType): DataField(name, resourceIdPatterns, textHintPatterns, informationType){
    val posssibleValue = ArrayList<String>()
    val random = java.util.Random(Random.nextLong())
    fun getValue(): String{
        if (posssibleValue.isEmpty())
            return ""
        else
            return posssibleValue[random.nextInt(posssibleValue.size)]
    }
}

class DayDataField(informationType: InformationType): AutoGeneratedDataField(
        name = "Day", informationType = informationType){
    init {
        var i:Int = 1
        while (i<=31)
        {
            posssibleValue.add(i.toString())
            i++
        }
    }
}

class MonthDataField(informationType: InformationType): AutoGeneratedDataField(
        name="Month", informationType = informationType){
    init {
        var i:Int = 1
        while (i<=12)
        {
            posssibleValue.add(i.toString())
            i++
        }
    }
}

