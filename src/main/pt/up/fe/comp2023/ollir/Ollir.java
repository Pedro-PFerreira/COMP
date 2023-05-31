package pt.up.fe.comp2023.ollir;

import pt.up.fe.comp.jmm.analysis.JmmSemanticsResult;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ollir.JmmOptimization;
import pt.up.fe.comp.jmm.ollir.OllirResult;

public class Ollir implements JmmOptimization {

    public OllirResult toOllir(JmmSemanticsResult var1){

        JmmNode rootNode = var1.getRootNode();
        String config = "";

        OllirVisitor ollirVisitor = new OllirVisitor(config, var1.getSymbolTable());

        System.out.println("Generating OLLIR:");

        config = ollirVisitor.visit(rootNode);

        System.out.println("OLLIR Code:");

        //System.out.println(config);

        return new OllirResult(var1, ollirVisitor.getOllirCode(), var1.getReports());
    }

}
