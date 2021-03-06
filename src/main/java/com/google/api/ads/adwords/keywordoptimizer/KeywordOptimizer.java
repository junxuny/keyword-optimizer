// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.ads.adwords.keywordoptimizer;

import com.google.api.ads.adwords.axis.v201509.cm.KeywordMatchType;
import com.google.api.ads.adwords.axis.v201509.cm.Money;
import com.google.api.ads.common.lib.conf.ConfigurationLoadException;
import com.google.api.ads.common.lib.exception.OAuthException;
import com.google.api.ads.common.lib.exception.ValidationException;
import com.google.common.base.Joiner;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * Main class for this tool, taking command line parameters and starting the optimization process
 * using an {@link Optimizer}.
 */
public class KeywordOptimizer {
  private static final Logger logger = LoggerFactory.getLogger(KeywordOptimizer.class);
  private static final String DEFAULT_PROPERTIES_PATH = "/keyword-optimizer.properties";
  private static final String ADS_PROPERTIES_DEFAULT_PATH = "ads.properties";
  private static final int LINE_MAX_WIDTH = 80;
  private static final Joiner CSV_JOINER = Joiner.on(",");
  private static final String[] CSV_HEADERS = {"Keyword", "Match Type", "Score",
      "Impressions (min)", "Impressions (mean)", "Impressions (max)", "Clicks (min)",
      "Clicks (mean)", "Clicks (max)", "Ctr (min)", "Ctr (mean)", "Ctr (max)",
      "Avg. Position (min)", "Avg. Position (mean)", "Avg. Position (max)", "Avg. Cpc (min)",
      "Avg. Cpc (mean)", "Avg. Cpc (max)", "Cost (min)", "Cost (mean)", "Cost (max)"};

  /**
   * Main method called from the command line.
   *
   * @param args command line arguments
   * @throws KeywordOptimizerException in case of an exception during the optimization process
   */
  public static void main(String[] args) throws KeywordOptimizerException {
    Options options = createCommandLineOptions();

    try {
      run(args);
    } catch (KeywordOptimizerException e) {
      // If the reason was a parsing error, then print help screen.
      if (e.getCause() != null && e.getCause() instanceof ParseException) {
        printHelp(options);
      }
      log("An error occurred: " + e.getMessage());
      throw e;
    }
  }

  /**
   * Method that runs the optimizer (own method for separate testing with exceptions).
   *
   * @param cmd the command line
   * @throws KeywordOptimizerException in case of an exception during the optimization process
   */
  public static void run(String cmd) throws KeywordOptimizerException {
    if (cmd.isEmpty()) {
      run(new String[] {});
    } else {
      run(cmd.split(" "));
    }
  }

  /**
   * Method that runs the optimizer (own method for testing with exceptions).
   *
   * @param args command line arguments
   * @throws KeywordOptimizerException in case of an exception during the optimization process
   */
  public static void run(String[] args) throws KeywordOptimizerException {
    Options options = createCommandLineOptions();

    CommandLineParser parser = new BasicParser();
    CommandLine cmdLine = null;
    try {
      cmdLine = parser.parse(options, args);
    } catch (ParseException e) {
      throw new KeywordOptimizerException("Error parsing command line parameters", e);
    }

    if (args.length == 0 || cmdLine.hasOption("h")) {
      printHelp(options);
      return;
    }

    logHeadline("Startup");

    OptimizationContext context = createContext(cmdLine);

    SeedGenerator seedGenerator = getSeedGenerator(cmdLine, context);
    addCpc(cmdLine, seedGenerator);
    addMatchTypes(cmdLine, seedGenerator);
    addLocations(cmdLine, seedGenerator);
    addLanguages(cmdLine, seedGenerator);

    AlternativesFinder alternativesFinder = createObjectBasedOnProperty(
        AlternativesFinder.class, KeywordOptimizerProperty.AlternativesFinderClass, context);
    TrafficEstimator estimator = createObjectBasedOnProperty(
        TrafficEstimator.class, KeywordOptimizerProperty.EstimatorClass, context);
    ScoreCalculator scoreCalculator = createObjectBasedOnProperty(
        ScoreCalculator.class, KeywordOptimizerProperty.ScoreCalculatorClass, context);

    Evaluator evaluator =
        new EstimatorBasedEvaluator(new CachedEstimator(estimator), scoreCalculator);

    RoundStrategy roundStrategy = createObjectBasedOnProperty(
        RoundStrategy.class, KeywordOptimizerProperty.RoundStrategyClass, context);

    Optimizer optimizer =
        new Optimizer(seedGenerator, alternativesFinder, evaluator, roundStrategy);

    logHeadline("Optimization");
    KeywordCollection bestKeywords = optimizer.optimize();
    output(cmdLine, bestKeywords);
  }

  /**
   * Creates the command line structure / options.
   *
   * @return the command line {@link Options}
   */
  private static Options createCommandLineOptions() {
    Options options = new Options();

    OptionBuilder.withLongOpt("keyword-properties");
    OptionBuilder.withDescription("Location of the keyword-optimizer.properties file.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("kp"));

    OptionBuilder.withLongOpt("ads-properties");
    OptionBuilder.withDescription("Location of the ads.properties file.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("ap"));

    OptionBuilder.withLongOpt("help");
    OptionBuilder.withDescription("Shows this help screen.");
    OptionBuilder.withArgName("help");
    options.addOption(OptionBuilder.create("h"));

    OptionBuilder.withLongOpt("seed-keywords");
    OptionBuilder.withDescription(
        "Use the given keywords (separated by spaces) as a seed for the optimization."
        + "\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(Option.UNLIMITED_VALUES);
    OptionBuilder.withArgName("keywords");
    options.addOption(OptionBuilder.create("sk"));

    OptionBuilder.withLongOpt("seed-keywords-file");
    OptionBuilder.withDescription(
        "Use the keywords from the given file (one keyword per row) as a seed for the optimization."
        + "\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("skf"));

    OptionBuilder.withLongOpt("seed-terms");
    OptionBuilder.withDescription(
        "Use the given search terms (separated by spaces) as a seed for the optimization."
        + "\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(Option.UNLIMITED_VALUES);
    OptionBuilder.withArgName("terms");
    options.addOption(OptionBuilder.create("st"));

    OptionBuilder.withLongOpt("seed-terms-file");
    OptionBuilder.withDescription(
        "Use the search terms from the given file (one keyword per row) as a seed "
        + "for the optimization.\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("stf"));

    OptionBuilder.withLongOpt("seed-urls");
    OptionBuilder.withDescription(
        "Use the given urls (separated by spaces) to extract keywords as a seed for "
        + "the optimization.\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(Option.UNLIMITED_VALUES);
    OptionBuilder.withArgName("urls");
    options.addOption(OptionBuilder.create("su"));

    OptionBuilder.withLongOpt("seed-urls-file");
    OptionBuilder.withDescription(
        "Use the urls from the given file (one url per row) to extract keywords as a seed "
        + "for the optimization.\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("suf"));

    OptionBuilder.withLongOpt("seed-category");
    OptionBuilder.withDescription(
        "Use the given category (ID as defined @ https://goo.gl/xUEr6s) to get keywords as a seed "
        + "for the optimization.\nNote: Only one seed-* option is allowed.");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("id");
    options.addOption(OptionBuilder.create("sc"));

    OptionBuilder.withLongOpt("match-types");
    OptionBuilder.withDescription("Use the given keyword match types (EXACT, BROAD, PHRASE).");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(3);
    OptionBuilder.withArgName("types");
    options.addOption(OptionBuilder.create("m"));

    OptionBuilder.withLongOpt("max-cpc");
    OptionBuilder.withDescription("Use the given maximum CPC (in USD, e.g., 5.0 for $5).");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("double");
    options.addOption(OptionBuilder.create("cpc"));

    OptionBuilder.withLongOpt("locations");
    OptionBuilder.withDescription(
        "Use the given locations IDs (ID as defined @ https://goo.gl/TA5E81) for "
        + "geo-targeted results.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(Option.UNLIMITED_VALUES);
    OptionBuilder.withArgName("ids");
    options.addOption(OptionBuilder.create("loc"));

    OptionBuilder.withLongOpt("languages");
    OptionBuilder.withDescription(
        "Use the given locations IDs (ID as defined @ https://goo.gl/WWzifs) for "
        + "language-targeted results.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(Option.UNLIMITED_VALUES);
    OptionBuilder.withArgName("ids");
    options.addOption(OptionBuilder.create("lang"));

    OptionBuilder.withLongOpt("output");
    OptionBuilder.withDescription(
        "Mode for outputting results (CONSOLE / CSV)\nNote: If set to CSV, then "
        + "option -of also has to be specified.");
    OptionBuilder.hasArg(true);
    OptionBuilder.hasArgs(2);
    OptionBuilder.withArgName("mode");
    options.addOption(OptionBuilder.create("o"));

    OptionBuilder.withLongOpt("output-file");
    OptionBuilder.withDescription(
        "File to for writing output data (only needed if option -o is specified).");
    OptionBuilder.hasArg(true);
    OptionBuilder.withArgName("file");
    options.addOption(OptionBuilder.create("of"));

    return options;
  }

  /**
   * Prints the help screen.
   *
   * @param options the expected command line parameters
   */
  private static void printHelp(Options options) {
    // Automatically generate the help statement.
    HelpFormatter formatter = new HelpFormatter();
    formatter.setWidth(LINE_MAX_WIDTH);

    // Comparator to show non-argument options first.
    formatter.setOptionComparator(new Comparator<Option>() {
      @Override
      public int compare(Option o1, Option o2) {
        if (o1.hasArg() && !o2.hasArg()) {
          return 1;
        }
        if (!o1.hasArg() && o2.hasArg()) {
          return -1;
        }

        return o1.getOpt().compareTo(o2.getOpt());
      }
    });

    System.out.println("Keyword Optimizer - BETA");
    System.out.println("------------------------");
    System.out.println();
    System.out.println("This utility can be used creating a optimizing and finding a set of "
        + "'good' keywords. It uses the TargetingIdeaService / \n"
        + "TrafficEstimatorService of the AdWords API to first obtain a set "
        + "of seed keywords and then perform a round-based \nprocess for "
        + "optimizing them.");
    System.out.println();
    formatter.printHelp("keyword-optimizer", options);
    System.out.println();
  }

  /**
   * Adds the match types specified from the command line to the {@link SeedGenerator}.
   *
   * @param cmdLine the parsed command line parameters
   * @param seedGenerator the previously constructed {@link SeedGenerator}
   * @throws KeywordOptimizerException in case no match type has been specified
   */
  private static void addMatchTypes(CommandLine cmdLine, SeedGenerator seedGenerator)
      throws KeywordOptimizerException {
    if (!cmdLine.hasOption("m")) {
      throw new KeywordOptimizerException(
          "Parameter 'match-types' is not specified, please specify "
          + "at least one match type for your keywords");
    }

    for (String matchType : cmdLine.getOptionValues("m")) {
      KeywordMatchType mt = KeywordMatchType.fromString(matchType);

      log("Using match type: " + mt);
      seedGenerator.addMatchType(mt);
    }
  }

  /**
   * Adds the maximum CPC specified from the command line to the {@link SeedGenerator}.
   *
   * @param cmdLine the parsed command line parameters
   * @param seedGenerator the previously constructed {@link SeedGenerator}
   * @throws KeywordOptimizerException in case no max. CPC has been specified
   */
  private static void addCpc(CommandLine cmdLine, SeedGenerator seedGenerator)
      throws KeywordOptimizerException {
    if (!cmdLine.hasOption("cpc")) {
      throw new KeywordOptimizerException("Parameter 'max-cpc' is not specified, please specify "
          + "the maximum cost per click");
    }

    double cpc = Double.parseDouble(cmdLine.getOptionValue("cpc"));

    Money maxCpc = new Money();
    maxCpc.setMicroAmount((long) (cpc * 1000000d));

    log("Using max cpc: " + cpc);
    seedGenerator.setMaxCpc(maxCpc);
  }

  /**
   * Adds the languages specified from the command line to the {@link SeedGenerator}.
   *
   * @param cmdLine the parsed command line parameters
   * @param seedGenerator the previously constructed {@link SeedGenerator}
   */
  private static void addLanguages(CommandLine cmdLine, SeedGenerator seedGenerator) {
    if (!cmdLine.hasOption("lang")) {
      return;
    }

    for (String language : cmdLine.getOptionValues("lang")) {
      long lang = Long.parseLong(language);

      log("Using language: " + lang);
      seedGenerator.addAdditionalLanguage(lang);
    }
  }

  /**
   * Adds the locations specified from the command line to the {@link SeedGenerator}.
   *
   * @param cmdLine the parsed command line parameters
   * @param seedGenerator the previously constructed {@link SeedGenerator}
   */
  private static void addLocations(CommandLine cmdLine, SeedGenerator seedGenerator) {
    if (!cmdLine.hasOption("loc")) {
      return;
    }

    for (String location : cmdLine.getOptionValues("loc")) {
      long loc = Long.parseLong(location);

      log("Using location: " + loc);
      seedGenerator.addAdditionalLocation(loc);
    }
  }

  /**
   * Creates the seed generator based on the command line options.
   *
   * @param cmdLine the parsed command line parameters
   * @param context holding shared objects during the optimization process
   * @return a {@link SeedGenerator} object
   * @throws KeywordOptimizerException in case of an error constructing the seed generator
   */
  private static SeedGenerator getSeedGenerator(CommandLine cmdLine, OptimizationContext context)
      throws KeywordOptimizerException {
    Option seedOption = getOnlySeedOption(cmdLine);

    if ("sk".equals(seedOption.getOpt())) {
      String[] keywords = cmdLine.getOptionValues("sk");

      SimpleSeedGenerator seedGenerator = new SimpleSeedGenerator();
      for (String keyword : keywords) {
        log("Using seed keyword: " + keyword);
        seedGenerator.addKeyword(keyword);
      }

      return seedGenerator;
    } else if ("skf".equals(seedOption.getOpt())) {
      List<String> keywords = loadFromFile(cmdLine.getOptionValue("skf"));

      SimpleSeedGenerator seedGenerator = new SimpleSeedGenerator();
      for (String keyword : keywords) {
        log("Using seed keyword: " + keyword);
        seedGenerator.addKeyword(keyword);
      }

      return seedGenerator;
    } else if ("st".equals(seedOption.getOpt())) {
      String[] keywords = cmdLine.getOptionValues("st");

      TisSearchTermsSeedGenerator seedGenerator = new TisSearchTermsSeedGenerator(context, null);
      for (String keyword : keywords) {
        log("Using seed search term: " + keyword);
        seedGenerator.addSearchTerm(keyword);
      }

      return seedGenerator;
    } else if ("stf".equals(seedOption.getOpt())) {
      List<String> terms = loadFromFile(cmdLine.getOptionValue("skf"));

      TisSearchTermsSeedGenerator seedGenerator = new TisSearchTermsSeedGenerator(context, null);
      for (String term : terms) {
        log("Using seed serach term: " + term);
        seedGenerator.addSearchTerm(term);
      }

      return seedGenerator;
    } else if ("su".equals(seedOption.getOpt())) {
      String[] urls = cmdLine.getOptionValues("su");

      TisUrlSeedGenerator seedGenerator = new TisUrlSeedGenerator(context, null);
      for (String url : urls) {
        log("Using seed url: " + url);
        seedGenerator.addUrl(url);
      }

      return seedGenerator;
    } else if ("suf".equals(seedOption.getOpt())) {
      List<String> urls = loadFromFile(cmdLine.getOptionValue("suf"));

      TisUrlSeedGenerator seedGenerator = new TisUrlSeedGenerator(context, null);
      for (String url : urls) {
        log("Using seed url: " + url);
        seedGenerator.addUrl(url);
      }

      return seedGenerator;
    } else if ("sc".equals(seedOption.getOpt())) {
      int category = Integer.parseInt(seedOption.getValue());
      log("Using seed category: " + category);
      TisCategorySeedGenerator seedGenerator =
          new TisCategorySeedGenerator(context, category, null);
      return seedGenerator;
    }

    throw new KeywordOptimizerException(
        "Seed option " + seedOption.getOpt() + " is not supported yet");
  }

  /**
   * Returns the only specified 'seed' option or an exception if no or more than one is specified.
   *
   * @param cmdLine the parsed command line parameters
   * @return the 'seed' {@link Option}
   * @throws KeywordOptimizerException in case there is no or more than one 'seed' parameter
   *         specified
   */
  private static Option getOnlySeedOption(CommandLine cmdLine) throws KeywordOptimizerException {
    Option seedOption = null;

    for (Option option : cmdLine.getOptions()) {
      if (option.getOpt().startsWith("s")) {
        if (seedOption != null) {
          throw new KeywordOptimizerException("Only one 'seed' option is allowed "
              + "(remove either " + seedOption.getOpt() + " or " + option.getOpt() + ")");
        }

        seedOption = option;
      }
    }

    if (seedOption == null) {
      throw new KeywordOptimizerException("You must specify a 'seed' parameter");
    }

    return seedOption;
  }

  /**
   * Outputs the results based on the command line parameters.
   *
   * @param cmdLine the parsed command line parameters
   * @param bestKeywords the optimized set of keywords
   * @throws KeywordOptimizerException in case there is no output file specified
   */
  private static void output(CommandLine cmdLine, KeywordCollection bestKeywords)
      throws KeywordOptimizerException {
    if (!cmdLine.hasOption("o")) {
      outputCsv(cmdLine, bestKeywords);
    } else {
      for (String mode : cmdLine.getOptionValues("o")) {
        if ("CONSOLE".equalsIgnoreCase(mode)) {
          outputConsole(bestKeywords);
        } else if ("CSV".equalsIgnoreCase(mode)) {
          outputCsv(cmdLine, bestKeywords);
        } else {
          throw new KeywordOptimizerException("Output mode '" + mode + "' is not supported");
        }
      }
    }
  }

  /**
   * Outputs the results on the console (sorted, best first).
   *
   * @param bestKeywords the optimized set of keywords
   */
  private static void outputConsole(KeywordCollection bestKeywords) {
    logHeadline("Results");
    for (KeywordInfo keyword : bestKeywords.getListSortedByScore()) {
      log(keyword.toString());
    }
  }

  /**
   * Outputs the results as a csv file (sorted, best first).
   *
   * @param cmdLine the parsed command line parameters
   * @param bestKeywords the optimized set of keywords
   * @throws KeywordOptimizerException in case there is a problem writing to the output file
   */
  private static void outputCsv(CommandLine cmdLine, KeywordCollection bestKeywords)
      throws KeywordOptimizerException {
    if (!cmdLine.hasOption("of")) {
      throw new KeywordOptimizerException("No output file (option -of specified)");
    }

    try {
      PrintStream printer = new PrintStream(cmdLine.getOptionValue("of"));
      printer.println(CSV_JOINER.join(CSV_HEADERS));

      for (KeywordInfo eval : bestKeywords.getListSortedByScore()) {
        TrafficEstimate estimate = eval.getEstimate();
        Object[] rowData = {
          eval.getKeyword().getText(),
          eval.getKeyword().getMatchType(),
          eval.getScore(),
          KeywordOptimizerUtil.formatCsv(estimate.getMin().getImpressionsPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMean().getImpressionsPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMax().getImpressionsPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMin().getClicksPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMean().getClicksPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMax().getClicksPerDay()),
          KeywordOptimizerUtil.formatCsv(estimate.getMin().getClickThroughRate()),
          KeywordOptimizerUtil.formatCsv(estimate.getMean().getClickThroughRate()),
          KeywordOptimizerUtil.formatCsv(estimate.getMax().getClickThroughRate()),
          KeywordOptimizerUtil.formatCsv(estimate.getMin().getAveragePosition()),
          KeywordOptimizerUtil.formatCsv(estimate.getMean().getAveragePosition()),
          KeywordOptimizerUtil.formatCsv(estimate.getMax().getAveragePosition()),
          KeywordOptimizerUtil.format(estimate.getMin().getAverageCpc()),
          KeywordOptimizerUtil.format(estimate.getMean().getAverageCpc()),
          KeywordOptimizerUtil.format(estimate.getMax().getAverageCpc()),
          KeywordOptimizerUtil.format(estimate.getMin().getTotalCost()),
          KeywordOptimizerUtil.format(estimate.getMean().getTotalCost()),
          KeywordOptimizerUtil.format(estimate.getMax().getTotalCost())
        };

        printer.println(CSV_JOINER.join(rowData));
      }
      printer.close();
    } catch (IOException e) {
      throw new KeywordOptimizerException("Error writing to output file", e);
    }
  }

  /**
   * Loads the properties from a file specified by the commandLine.
   *
   * @param cmdLine the parsed command line parameters
   * @throws KeywordOptimizerException in case there is a problem reading the configuration file
   */
  private static Configuration loadConfiguration(CommandLine cmdLine)
      throws KeywordOptimizerException {
    PropertiesConfiguration properties = new PropertiesConfiguration();

    try {
      if (cmdLine.hasOption("kp")) {
        // If properties file specified by parameter, then load from this file.
        String propertiesPath = cmdLine.getOptionValue("kp");
        File propertiesFile = new File(propertiesPath);
        log("Using keyword optimizer properties file: " + propertiesFile.getAbsolutePath());
        FileInputStream is = new FileInputStream(propertiesFile);
        properties.load(is);
        is.close();
      } else {
        // Otherwise load it from classpath.
        log("Loading keyword optimizer properties from classpath");
        InputStream is = KeywordOptimizer.class.getResourceAsStream(DEFAULT_PROPERTIES_PATH);
        properties.load(is);
        is.close();
      }
    } catch (IOException e) {
      throw new KeywordOptimizerException("Error loading the configuration file", e);
    } catch (ConfigurationException e) {
      throw new KeywordOptimizerException("Error parsing the configuration file", e);
    }

    return properties;
  }

  /**
   * Creates and initializes optimization context, holding the AdWords API Util.
   *
   * @param cmdLine the parsed command line parameters
   * @throws KeywordOptimizerException in case of an error constructing the optimization context
   */
  private static OptimizationContext createContext(CommandLine cmdLine)
      throws KeywordOptimizerException {
    // If no path is specified, use default path.
    String adsPropertiesPath = ADS_PROPERTIES_DEFAULT_PATH;
    if (cmdLine.hasOption("ap")) {
      adsPropertiesPath = cmdLine.getOptionValue("ap");
    }
    log("Using ads properties file: " + adsPropertiesPath);

    try {
      AdWordsApiUtil util = new AdWordsApiUtil(adsPropertiesPath);
      Configuration configuration = loadConfiguration(cmdLine);

      return new OptimizationContext(configuration, util);
    } catch (ConfigurationLoadException e) {
      throw new KeywordOptimizerException("Error loading the ads properties file", e);
    } catch (ValidationException e) {
      throw new KeywordOptimizerException("Missing refresh token", e);
    } catch (OAuthException e) {
      throw new KeywordOptimizerException("Authentication error", e);
    }
  }

  /**
   * Reads settings (keywords / urls / serach terms) line-by-line from a file.
   *
   * @param fileName the name of the file to read from
   * @return a {@link List} of strings line-by-line
   * @throws KeywordOptimizerException in case there is a problem reading the file
   */
  private static List<String> loadFromFile(String fileName) throws KeywordOptimizerException {
    List<String> out = new ArrayList<String>();

    Scanner scan = null;
    try {
      scan = new Scanner(new File(fileName));
      while (scan.hasNextLine()) {
        String line = scan.nextLine().trim();

        // Ignore comment lines.
        if (line.startsWith("#")) {
          continue;
        }

        out.add(line);
      }
      return out;
    } catch (IOException e) {
      throw new KeywordOptimizerException("Error loading file '" + fileName + "'", e);
    } finally {
      if (scan != null) {
        scan.close();
      }
    }
  }

  /**
   * Helper method to create an object based on a class name specified in the properties file. Note
   * that all classes instantiated here must provide an empty constructor.
   *
   * @param clazz the super class of the object to be created
   * @param property the property that has the class name as a value
   * @param context holding shared objects during the optimization process
   * @return the instantiated object
   * @throws KeywordOptimizerException in case of a problem lading the specified object
   */
  @SuppressWarnings(value = "unchecked")
  private static <Type> Type createObjectBasedOnProperty(
      Class<Type> clazz, KeywordOptimizerProperty property, OptimizationContext context)
      throws KeywordOptimizerException {
    String className = context.getConfiguration().getString(property.getName());
    if (className == null || className.isEmpty()) {
      throw new KeywordOptimizerException(
          "Mandatory property '" + property.getName() + "' is missing");
    }

    try {
      Class<Type> dynamicClazz = (Class<Type>) Class.forName(className);

      // First try if there is a constructor expecting the optimization context.
      try {
        Constructor<Type> constructor = dynamicClazz.getConstructor(OptimizationContext.class);
        Type obj = constructor.newInstance(context);
        return clazz.cast(obj);
      } catch (NoSuchMethodException e) {
        // Ignore.
      }

      // Else use default constructor.
      Constructor<Type> constructor = dynamicClazz.getConstructor();
      Type obj = constructor.newInstance();
      return clazz.cast(obj);
    } catch (ReflectiveOperationException e) {
      throw new KeywordOptimizerException("Error constructing '" + className + "'");
    }
  }

  /**
   * Prints out a log message.
   *
   * @param msg the message
   */
  private static void log(String msg) {
    System.out.println(msg);
    logger.info(msg);
  }

  /**
   * Prints out a headline message.
   *
   * @param msg the headline
   */
  private static void logHeadline(String msg) {
    String txt = "======== " + msg + " ==========";
    log(txt);
  }
}
