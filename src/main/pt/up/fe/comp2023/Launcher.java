package pt.up.fe.comp2023;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import pt.up.fe.comp.TestUtils;
import pt.up.fe.comp.jmm.analysis.JmmSemanticsResult;
import pt.up.fe.comp.jmm.jasmin.JasminResult;
import pt.up.fe.comp.jmm.ollir.OllirResult;
import pt.up.fe.comp.jmm.parser.JmmParserResult;
import pt.up.fe.comp2023.analysis.Analysis;
import pt.up.fe.comp2023.jasmin.JasminGenerator;
import pt.up.fe.comp2023.ollir.Ollir;
import pt.up.fe.comp2023.symbol.table.Table;
import pt.up.fe.comp2023.symbol.table.TableVisitor;
import pt.up.fe.specs.util.SpecsIo;
import pt.up.fe.specs.util.SpecsLogs;
import pt.up.fe.specs.util.SpecsSystem;

public class Launcher {

    public static void main(String[] args) {
        // Setups console logging and other things
        SpecsSystem.programStandardInit();

        // Parse arguments as a map with predefined options
        var config = parseArgs(args);

        // Get input file
        File inputFile = new File(config.get("inputFile"));
        // Check if file exists
        if (!inputFile.isFile()) {
            throw new RuntimeException("Expected a path to an existing input file, got '" + inputFile + "'.");
        }


        // Read contents of input file
        String code = SpecsIo.read(inputFile);

        // Instantiate JmmParser
        SimpleParser parser = new SimpleParser();

        // Parse stage
        JmmParserResult parserResult = parser.parse(code, config);

        // Remove after testing

        Table table = new Table();
        TableVisitor visitor = new TableVisitor(table);
        visitor.visit(parserResult.getRootNode(), "");
        System.out.println("Called semanticAnalysis");
        // ------------------------


        // Check if there are parsing errors
        TestUtils.noErrors(parserResult.getReports());

        //Prints the tree nodes
        System.out.println(parserResult.getRootNode().toTree());

        // ... add remaining stages
        Analysis analysis = new Analysis();

        System.out.println("\n\nPrinting Symbol Table\n");

        JmmSemanticsResult result = new Analysis().semanticAnalysis(parserResult);
        analysis.semanticAnalysis(parserResult);

        //JmmSemanticsResult result = new JmmSemanticsResult(parserResult.getRootNode(), table, parserResult.getReports(), parserResult.getConfig());

        if (result.getReports().size() > 0) {
            System.out.println("Semantic Errors were detected. Aborting execution...");
        } else {
            OllirResult ollirResult = new Ollir().toOllir(result);

            System.out.println("ollirResult: " + ollirResult.getOllirCode());

            JasminResult jasminResult = new JasminGenerator().toJasmin(ollirResult);
            System.out.println("jasminResult:\n" + jasminResult.getJasminCode());
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        SpecsLogs.info("Executing with args: " + Arrays.toString(args));

        // Check if there is at least one argument
        if (args.length != 1) {
            throw new RuntimeException("Expected a single argument, a path to an existing input file.");
        }

        // Create config
        Map<String, String> config = new HashMap<>();
        config.put("inputFile", args[0]);
        config.put("optimize", "false");
        config.put("registerAllocation", "-1");
        config.put("debug", "false");

        return config;
    }
}

