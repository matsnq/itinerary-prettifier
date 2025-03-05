import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Prettifier {

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("-h")) {
            printUsage();
            return;
        }
        if (args.length != 3) {
            System.err.println("\033[31mError: Invalid number of arguments.\033[0m");
            printUsage();
            return;
        }
        String inputFilePath = args[0];
        String outputFilePath = args[1];
        String airportLookupFilePath = args[2];

        if (!Files.exists(Paths.get(inputFilePath))) {
            System.err.println("\033[31mError: Input file not found.\033[0m");
            return;
        }

        if (!Files.exists(Paths.get(airportLookupFilePath))) {
            System.err.println("\033[31mError: Airport lookup file not found.\033[0m");
            return;
        }

        try {
            Map<String, String> iataLookup = new HashMap<>();
            Map<String, String> icaoLookup = new HashMap<>();
            loadAirportLookup(airportLookupFilePath, iataLookup, icaoLookup);
            processItinerary(inputFilePath, outputFilePath, iataLookup, icaoLookup);
        } catch (IOException e) {
            System.err.println("\033[31mError: \033[0m" + e.getMessage());
        }

    }

    private static void printUsage() {
        System.out.println("\033[33mHow to use Itinerary:\033[0m");
        System.out.println("\033[32mjava Prettifier.java ./input.txt ./output.txt ./airport-lookup.csv\033[0m");
    }

    private static void loadAirportLookup(String airportLookupFilePath, Map<String, String> iataLookup,
            Map<String, String> icaoLookup) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(airportLookupFilePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("\033[31mAirport lookup malformed: Missing header row.\033[0m");
            }

            String[] headers = headerLine.split(",");
            int iataIndex = -1, icaoIndex = -1, nameIndex = -1, cityIndex = -1, countryIndex = -1, coordIndex = -1;

            for (int i = 0; i < headers.length; i++) {
                switch (headers[i].trim()) {
                    case "iata_code":
                        iataIndex = i;
                        break;
                    case "icao_code":
                        icaoIndex = i;
                        break;
                    case "name":
                        nameIndex = i;
                        break;
                    case "municipality":
                        cityIndex = i;
                        break;
                    case "iso_country":
                        countryIndex = i;
                        break;
                    case "coordinates":
                        coordIndex = i;
                        break;
                }
            }

            if (iataIndex == -1 || icaoIndex == -1 || nameIndex == -1 || cityIndex == -1 || countryIndex == -1
                    || coordIndex == -1) {
                throw new IOException("\033[31mAirport lookup malformed: Missing required columns.\033[0m");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(",");
                if (tokens.length < headers.length || Arrays.stream(tokens).anyMatch(String::isEmpty)) {
                    throw new IOException("\033[31mAirport lookup malformed: Empty columns in data.\033[0m");
                }

                String name = tokens[nameIndex].trim();
                String city = tokens[cityIndex].trim();
                String iataCode = tokens[iataIndex].trim();
                String icaoCode = tokens[icaoIndex].trim();
                String country = tokens[countryIndex].trim();

                if (!iataCode.isEmpty()) {
                    iataLookup.put(iataCode, name + " (" + city + ", " + country + ")");
                }
                if (!icaoCode.isEmpty()) {
                    icaoLookup.put(icaoCode, name + " (" + city + ", " + country + ")");
                }
            }
        }
    }

    private static void processItinerary(String inputFilePath, String outputFilePath, Map<String, String> iataLookup,
            Map<String, String> icaoLookup) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(inputFilePath));
        List<String> outputLines = new ArrayList<>();

        for (String line : lines) {
            line = convertVerticalWhitespaceToNewLine(line);
            line = processAirportCodes(line, iataLookup, icaoLookup);
            line = processDatesAndTimes(line);

            if (line.trim().isEmpty() && !outputLines.isEmpty() && outputLines.get(outputLines.size() - 1).isEmpty()) {
                continue;
            }

            outputLines.add(line);
        }

        Files.write(Paths.get(outputFilePath), outputLines);
    }

    private static String convertVerticalWhitespaceToNewLine(String line) {
        line = line.replace("\\n", "\n");
        line = line.replace("\\r", "\n");
        line = line.replace("\\v", "\n");
        line = line.replace("\\f", "\n");

        line = line.replace("\u000B", "\n");
        line = line.replace("\u000C", "\n");
        line = line.replace("\r", "\n");
        return line;
    }

    private static String processAirportCodes(String line, Map<String, String> iataLookup,
            Map<String, String> icaoLookup) {
        Pattern icaoPattern = Pattern.compile("##([A-Z]{4})|\\*##([A-Z]{4})");
        Matcher icaoMatcher = icaoPattern.matcher(line);
        StringBuffer result = new StringBuffer();

        while (icaoMatcher.find()) {
            String icaoCode = icaoMatcher.group(1) != null ? icaoMatcher.group(1) : icaoMatcher.group(2);
            String airportName = icaoLookup.getOrDefault(icaoCode, "##" + icaoCode);

            // Only split if airportName is valid, otherwise default to the code
            String cityName = airportName.contains("(") ? airportName.split(" \\(")[1].replace(")", "") : icaoCode;
            airportName = airportName.contains("(") ? airportName.split(" \\(")[0] : "##" + icaoCode;

            if (icaoMatcher.group(0).startsWith("*")) {
                icaoMatcher.appendReplacement(result, cityName); // Use city name for * prefixed codes
            } else {
                icaoMatcher.appendReplacement(result, airportName); // Use airport name for normal codes
            }
        }
        icaoMatcher.appendTail(result);

        Pattern iataPattern = Pattern.compile("#([A-Z]{3})|\\*#([A-Z]{3})");
        Matcher iataMatcher = iataPattern.matcher(result.toString());
        result = new StringBuffer();

        while (iataMatcher.find()) {
            String iataCode = iataMatcher.group(1) != null ? iataMatcher.group(1) : iataMatcher.group(2);
            String airportName = iataLookup.getOrDefault(iataCode, "#" + iataCode);

            // Only split if airportName is valid, otherwise default to the code
            String cityName = airportName.contains("(") ? airportName.split(" \\(")[1].replace(")", "") : iataCode;
            airportName = airportName.contains("(") ? airportName.split(" \\(")[0] : "#" + iataCode;

            if (iataMatcher.group(0).startsWith("*")) {
                iataMatcher.appendReplacement(result, cityName); // Use city name for * prefixed codes
            } else {
                iataMatcher.appendReplacement(result, airportName); // Use airport name for normal codes
            }
        }
        iataMatcher.appendTail(result);

        return result.toString();
    }

    private static String processDatesAndTimes(String line) {
        Pattern datePattern = Pattern.compile("D\\(([^)]+)\\)");
        Matcher dateMatcher = datePattern.matcher(line);
        StringBuffer result = new StringBuffer();

        while (dateMatcher.find()) {
            String isoDate = dateMatcher.group(1);
            String formattedDate;
            try {
                LocalDateTime dateTime = ZonedDateTime.parse(isoDate).toLocalDateTime();
                formattedDate = dateTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
            } catch (DateTimeParseException e) {
                formattedDate = dateMatcher.group();
            }
            dateMatcher.appendReplacement(result, formattedDate);
        }
        dateMatcher.appendTail(result);

        result = processTimes(result.toString(), "T12\\(([^)]+)\\)", "hh:mma");
        result = processTimes(result.toString(), "T24\\(([^)]+)\\)", "HH:mm");

        return result.toString().trim();
    }

    private static StringBuffer processTimes(String line, String timePattern, String outputFormat) {
        Pattern pattern = Pattern.compile(timePattern);
        Matcher matcher = pattern.matcher(line);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String isoDate = matcher.group(1);
            String formattedTime;
            try {
                ZonedDateTime dateTime = ZonedDateTime.parse(isoDate);

                formattedTime = dateTime.format(DateTimeFormatter.ofPattern(outputFormat));

                String timeZoneOffset = dateTime.getOffset().toString();
                if (timeZoneOffset.equals("Z")) {
                    timeZoneOffset = "(00:00)";
                } else {
                    timeZoneOffset = "(" + timeZoneOffset.replace("Z", "") + ")";
                }

                formattedTime += " " + timeZoneOffset;
            } catch (DateTimeParseException e) {
                formattedTime = matcher.group();
            }
            matcher.appendReplacement(result, formattedTime);
        }
        matcher.appendTail(result);
        return result;
    }
}