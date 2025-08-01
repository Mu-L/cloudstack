//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.utils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StringUtils extends org.apache.commons.lang3.StringUtils {
    private static final char[] hexChar = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    private static Charset preferredACSCharset;
    private static final String UTF8 = "UTF-8";

    static {
        if (isUtf8Supported()) {
            preferredACSCharset = Charset.forName(UTF8);
        } else {
            preferredACSCharset = Charset.defaultCharset();
        }
    }

    public static Charset getPreferredCharset() {
        return preferredACSCharset;
    }

    public static boolean isUtf8Supported() {
        return Charset.isSupported(UTF8);
    }

    protected static Charset getDefaultCharset() {
        return Charset.defaultCharset();
    }

    public static String cleanupTags(String tags) {
        if (tags != null) {
            final String[] tokens = tags.split(",");
            final StringBuilder t = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                t.append(tokens[i].trim()).append(",");
            }
            t.delete(t.length() - 1, t.length());
            tags = t.toString();
        }

        return tags;
    }

    /**
     * @param tags
     * @return List of tags
     */
    public static List<String> csvTagsToList(final String tags) {
        final List<String> tagsList = new ArrayList<String>();

        if (tags != null) {
            final String[] tokens = tags.split(",");
            for (int i = 0; i < tokens.length; i++) {
                tagsList.add(tokens[i].trim());
            }
        }

        return tagsList;
    }

    /**
     * Converts a List of tags to a comma separated list
     * @param tagsList
     * @return String containing a comma separated list of tags
     */

    public static String listToCsvTags(final List<String> tagsList) {
        final StringBuilder tags = new StringBuilder();
        if (tagsList.size() > 0) {
            for (int i = 0; i < tagsList.size(); i++) {
                tags.append(tagsList.get(i));
                if (i != tagsList.size() - 1) {
                    tags.append(',');
                }
            }
        }

        return tags.toString();
    }

    public static String getExceptionStackInfo(final Throwable e) {
        final StringBuffer sb = new StringBuffer();

        sb.append(e.toString()).append("\n");
        final StackTraceElement[] elemnents = e.getStackTrace();
        for (final StackTraceElement element : elemnents) {
            sb.append(element.getClassName()).append(".");
            sb.append(element.getMethodName()).append("(");
            sb.append(element.getFileName()).append(":");
            sb.append(element.getLineNumber()).append(")");
            sb.append("\n");
        }

        return sb.toString();
    }

    public static String unicodeEscape(final String s) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >> 7 > 0) {
                sb.append("\\u");
                sb.append(hexChar[c >> 12 & 0xF]); // append the hex character for the left-most 4-bits
                sb.append(hexChar[c >> 8 & 0xF]);  // hex for the second group of 4-bits from the left
                sb.append(hexChar[c >> 4 & 0xF]);  // hex for the third group
                sb.append(hexChar[c & 0xF]);         // hex for the last group, e.g., the right most 4-bits
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String getMaskedPasswordForDisplay(final String password) {
        if (password == null || password.isEmpty()) {
            return "*";
        }

        final StringBuffer sb = new StringBuffer();
        sb.append(password.charAt(0));
        for (int i = 1; i < password.length(); i++) {
            sb.append("*");
        }

        return sb.toString();
    }

    // removes a password request param and it's value, also considering password is in query parameter value which has been url encoded
    private static final Pattern REGEX_PASSWORD_QUERYSTRING = Pattern.compile("(&|%26)?[^(&|%26)]*((p|P)assword|accesskey|secretkey)(=|%3D).*?(?=(%26|[&'\"]|$))");

    // removes a password/accesskey/ property from a response json object
    private static final Pattern REGEX_PASSWORD_JSON = Pattern.compile("\"((p|P)assword|privatekey|accesskey|secretkey)\":\\s?\".*?\",?");

    private static final Pattern REGEX_PASSWORD_DETAILS = Pattern.compile("(&|%26)?details(\\[|%5B)\\d*(\\]|%5D)\\.key(=|%3D)((p|P)assword|accesskey|secretkey)(?=(%26|[&'\"]))");

    private static final Pattern REGEX_PASSWORD_DETAILS_INDEX = Pattern.compile("details(\\[|%5B)\\d*(\\]|%5D)");

    private static final Pattern REGEX_REDUNDANT_AND = Pattern.compile("(&|%26)(&|%26)+");

    // Responsible for stripping sensitive content from request and response strings
    public static String cleanString(final String stringToClean) {
        String cleanResult = "";
        if (stringToClean != null) {
            cleanResult = REGEX_PASSWORD_QUERYSTRING.matcher(stringToClean).replaceAll("");
            cleanResult = REGEX_PASSWORD_JSON.matcher(cleanResult).replaceAll("");
            final Matcher detailsMatcher = REGEX_PASSWORD_DETAILS.matcher(cleanResult);
            while (detailsMatcher.find()) {
                final Matcher detailsIndexMatcher = REGEX_PASSWORD_DETAILS_INDEX.matcher(detailsMatcher.group());
                if (detailsIndexMatcher.find()) {
                    cleanResult = cleanDetails(cleanResult, detailsIndexMatcher.group());
                }
            }
        }
        return cleanResult;
    }

    public static String cleanDetails(final String stringToClean, final String detailsIndexString) {
        String cleanResult = stringToClean;
        for (final String log : stringToClean.split("&|%26")) {
            if (log.contains(detailsIndexString)) {
                cleanResult = cleanResult.replace(log, "");
            }
        }
        cleanResult = REGEX_REDUNDANT_AND.matcher(cleanResult).replaceAll("&");
        return cleanResult;
    }

    public static boolean areTagsEqual(final String tags1, final String tags2) {
        if (tags1 == null && tags2 == null) {
            return true;
        }

        if (tags1 != null && tags2 == null) {
            return false;
        }

        if (tags1 == null && tags2 != null) {
            return false;
        }

        final String delimiter = ",";

        final List<String> lstTags1 = new ArrayList<String>();
        final String[] aTags1 = tags1.split(delimiter);

        for (final String tag1 : aTags1) {
            lstTags1.add(tag1.toLowerCase());
        }

        final List<String> lstTags2 = new ArrayList<String>();
        final String[] aTags2 = tags2.split(delimiter);

        for (final String tag2 : aTags2) {
            lstTags2.add(tag2.toLowerCase());
        }

        return lstTags1.containsAll(lstTags2) && lstTags2.containsAll(lstTags1);
    }

    public static Map<String, String> stringToMap(final String s) {
        final Map<String, String> map = new HashMap<String, String>();
        final String[] elements = s.split(";");
        for (final String parts : elements) {
            final String[] keyValue = parts.split(":");
            map.put(keyValue[0], keyValue[1]);
        }
        return map;
    }

    public static String mapToString(final Map<String, String> map) {
        String s = "";
        for (final Map.Entry<String, String> entry : map.entrySet()) {
            s += entry.getKey() + ":" + entry.getValue() + ";";
        }
        if (s.length() > 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static <T> List<T> applyPagination(final List<T> originalList, final Long startIndex, final Long pageSizeVal) {
        // Most likely pageSize will never exceed int value, and we need integer to partition the listToReturn
        final boolean applyPagination = startIndex != null && pageSizeVal != null
                && startIndex <= Integer.MAX_VALUE && startIndex >= 0 && pageSizeVal <= Integer.MAX_VALUE
                && pageSizeVal > 0;
        List<T> listWPagination = null;
        if (applyPagination) {
            listWPagination = new ArrayList<>();
            final int index = startIndex.intValue() == 0 ? 0 : startIndex.intValue() / pageSizeVal.intValue();
            final List<List<T>> partitions = StringUtils.partitionList(originalList, pageSizeVal.intValue());
            if (index < partitions.size()) {
                listWPagination = partitions.get(index);
            }
        }
        return listWPagination;
    }

    private static <T> List<List<T>> partitionList(final List<T> originalList, final int chunkSize) {
        final List<List<T>> listOfChunks = new ArrayList<List<T>>();
        for (int i = 0; i < originalList.size() / chunkSize; i++) {
            listOfChunks.add(originalList.subList(i * chunkSize, i * chunkSize + chunkSize));
        }
        if (originalList.size() % chunkSize != 0) {
            listOfChunks.add(originalList.subList(originalList.size() - originalList.size() % chunkSize, originalList.size()));
        }
        return listOfChunks;
    }

    public static String toCSVList(final List<String> csvList) {
        return org.apache.commons.lang3.StringUtils.defaultString(org.apache.commons.lang3.StringUtils.join(csvList, ","));
    }

    public static Pair<String, String> getKeyValuePairWithSeparator(String keyValuePair, String separator) {
        final int index = keyValuePair.indexOf(separator);
        final String key = keyValuePair.substring(0, index);
        final String value = keyValuePair.substring(index + 1);
        return new Pair<>(key.trim(), value.trim());
    }

    public static Map<String, String> parseJsonToMap(String jsonString) {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> mapResult = new HashMap<>();

        if (org.apache.commons.lang3.StringUtils.isNotBlank(jsonString)) {
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonString);
                jsonNode.fields().forEachRemaining(entry -> {
                    mapResult.put(entry.getKey(), entry.getValue().asText());
                });
            } catch (Exception e) {
                throw new CloudRuntimeException("Error while parsing json to convert it to map " + e.getMessage());
            }
        }

        return mapResult;
    }

    /**
     * Converts the comma separated numbers to ranges for any consecutive numbers in the input with numbers (and ranges)
     * Eg: "198,200-203,299,300,301,303,304,305,306,307,308,311,197" to "197-198,200-203,299-301,303-308,311"
     * @param inputNumbersAndRanges
     * @return String containing a converted ranges for any consecutive numbers
     */
    public static String numbersToRange(String inputNumbersAndRanges) {
        Set<Integer> numberSet = new TreeSet<>();
        for (String inputNumber : inputNumbersAndRanges.split(",")) {
            inputNumber = inputNumber.trim();
            if (inputNumber.contains("-")) {
                String[] range = inputNumber.split("-");
                if (range.length == 2 && range[0] != null && range[1] != null) {
                    int start = NumbersUtil.parseInt(range[0], 0);
                    int end = NumbersUtil.parseInt(range[1], 0);
                    for (int i = start; i <= end; i++) {
                        numberSet.add(i);
                    }
                }
            } else {
                numberSet.add(NumbersUtil.parseInt(inputNumber, 0));
            }
        }

        StringBuilder result = new StringBuilder();
        if (!numberSet.isEmpty()) {
            List<Integer> numbers = new ArrayList<>(numberSet);
            int startNumber = numbers.get(0);
            int endNumber = startNumber;

            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i) == endNumber + 1) {
                    endNumber = numbers.get(i);
                } else {
                    appendRange(result, startNumber, endNumber);
                    startNumber = endNumber = numbers.get(i);
                }
            }
            appendRange(result, startNumber, endNumber);
        }

        return result.toString();
    }

    private static void appendRange(StringBuilder sb, int startNumber, int endNumber) {
        if (sb.length() > 0) {
            sb.append(",");
        }
        if (startNumber == endNumber) {
            sb.append(startNumber);
        } else {
            sb.append(startNumber).append("-").append(endNumber);
        }
    }

    /**
     * Converts the comma separated numbers and ranges to numbers
     * Eg: "197-198,200-203,299-301,303-308,311" to "197,198,200,201,202,203,299,300,301,303,304,305,306,307,308,311"
     * @param inputNumbersAndRanges
     * @return String containing a converted numbers
     */
    public static String rangeToNumbers(String inputNumbersAndRanges) {
        Set<Integer> numberSet = new TreeSet<>();
        for (String inputNumber : inputNumbersAndRanges.split(",")) {
            inputNumber = inputNumber.trim();
            if (inputNumber.contains("-")) {
                String[] range = inputNumber.split("-");
                int startNumber = Integer.parseInt(range[0]);
                int endNumber = Integer.parseInt(range[1]);
                for (int i = startNumber; i <= endNumber; i++) {
                    numberSet.add(i);
                }
            } else {
                numberSet.add(Integer.parseInt(inputNumber));
            }
        }

        StringBuilder result = new StringBuilder();
        for (int number : numberSet) {
            if (result.length() > 0) {
                result.append(",");
            }
            result.append(number);
        }

        return result.toString();
    }

    public static String[] splitCommaSeparatedStrings(String... tags) {
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (tag != null && !tag.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(tag);
            }
        }
        String appendedTags = sb.toString();
        String[] finalMergedTagsArray = appendedTags.split(",");
        return finalMergedTagsArray;
    }


    /**
     * Converts the comma separated numbers and ranges to numbers
     * @param originalString the original string (can be null or empty) containing list of comma separated values that has to be updated
     * @param value the value to add to, or remove from the original string
     * @param add if true, adds the input value; if false, removes it
     * @return String containing the modified original string (or null if empty)
     */
    public static String updateCommaSeparatedStringWithValue(String originalString, String value, boolean add) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(value)) {
            return originalString;
        }

        Set<String> values = new LinkedHashSet<>();

        if (org.apache.commons.lang3.StringUtils.isNotEmpty(originalString)) {
            values.addAll(Arrays.stream(originalString.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
        }

        if (add) {
            values.add(value);
        } else {
            values.remove(value);
        }

        return values.isEmpty() ? null : String.join(",", values);
    }

    /**
     * Returns the first value from a comma-separated string.
     * @param inputString the input string (can be null or empty) containing list of comma separated values
     * @return the first value, or null if none found
     */
    public static String getFirstValueFromCommaSeparatedString(String inputString) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(inputString)) {
            return inputString;
        }

        String[] values = inputString.split(",");
        if (values.length > 0) {
            return values[0].trim();
        }

        return null;
    }
}
