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

package org.calm

import org.calm.simmetrics.LevenshteinList
import org.simmetrics.StringMetric
import org.simmetrics.builders.StringMetricBuilder.with
import org.simmetrics.metrics.CosineSimilarity
import org.simmetrics.metrics.Dice
import org.simmetrics.metrics.Jaccard
import org.simmetrics.metrics.Levenshtein
import org.simmetrics.metrics.LongestCommonSubsequence
import org.simmetrics.simplifiers.Simplifiers
import org.simmetrics.tokenizers.Tokenizers
import java.util.*

class StringComparison {
    companion object {

        fun compareStringsSimple(arg1: String, arg2: String): Float {
            if (arg1 == arg2)
                return 1.0f
            return 0.0f
        }

        fun compareStringsLevenshtein(arg1: String, arg2: String): Float {
            var result = 0f
            if (arg1 === "" || arg2 === "") {
                result = 0.0.toFloat()
                return result
            }
            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2: StringMetric = with(Levenshtein())
                .simplify(Simplifiers.toLowerCase(Locale.ENGLISH))
                .simplify(Simplifiers.replaceNonWord())
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }

        fun compareStringsXpathConsine(arg1: String, arg2: String): Float {
            var result = 0f

            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2 = with(CosineSimilarity())
                .tokenize(Tokenizers.pattern("/"))
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }

        fun compareStringsXpathLevenshtein(arg1: String, arg2: String): Float {
            var result = 0f

            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2 = with(LevenshteinList())
                .tokenize(Tokenizers.pattern("/"))
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }

        fun compareStringsXpathDice(arg1: String, arg2: String): Float {
            var result = 0f

            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2 = with(Dice())
                .tokenize(Tokenizers.pattern("/"))
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }

        fun compareStringsXpathJacard(arg1: String, arg2: String): Float {
            var result = 0f

            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2 = with(Jaccard())
                .tokenize(Tokenizers.pattern("/"))
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }

        fun compareStringsXpathCommonLongestSubsequence(arg1: String, arg2: String):Float {
            var result = 0f

            /*QGramsDistance ds2 = new QGramsDistance();*/
            val ds2 = with(LongestCommonSubsequence())
                .build()
            result = ds2.compare(arg1, arg2)
            return result
        }
        fun tokenizeString(arg: String): String {
            var result = ""
            for (br in 0 until arg.length) {
                result += arg[br].toString() + " "
            }
            return result
        }
    }
}