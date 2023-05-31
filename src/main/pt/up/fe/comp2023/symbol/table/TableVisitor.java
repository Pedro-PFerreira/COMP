package pt.up.fe.comp2023.symbol.table;

import pt.up.fe.comp.jmm.analysis.table.Symbol;
import pt.up.fe.comp.jmm.analysis.table.Type;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TableVisitor extends AJmmVisitor<String, String> {

    Table table;

    public TableVisitor(Table table) {
        this.table = table;
    }

    public Table getTable() {
        return table;
    }

    @Override
    protected void buildVisitor() {
        addVisit("Program", this::dealWithProgram);
        addVisit("ImportPackage", this::dealWithImports);
        addVisit("ClassDeclaration", this::dealWithClassDeclaration);
        addVisit("SuperclassName", this::dealWithSuper);
        addVisit("ImplementedClass", this::dealWithImplemented);
        addVisit("ClassBody", this::dealWithClassBody);
        addVisit("ClassField", this::dealWithFields);
        addVisit("ClassMethod", this::dealWithMethods);
        addVisit("ClassArrayMethod", this::dealWithMethods);
        addVisit("ClassName", this::dealWithClassName);
        addVisit("MethodBody", this::dealWithMethodBody);
        addVisit("MethodCalls", this::dealWithMethodCalls);
        addVisit("Return", this::dealWithReturn);
    }

    private String dealWithProgram(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");

        for (JmmNode child : jmmNode.getChildren()) {
            ret.append(visit(child, ""));
        }
        ret.append("\n}\n");

        return ret.toString();
    }

    private String dealWithReturn(JmmNode jmmNode, String s){

        StringBuilder ret = new StringBuilder(s != null ? s : "");

        for (JmmNode child : jmmNode.getChildren()){

            if (Objects.equals(child.getKind(), "MethodCalls")){
                ret.append(this.dealWithMethodCalls(child, s));
            }
            else if (!Objects.equals(child.getKind(), "BinaryOp") && !Objects.equals(child.getKind(), "ArrayAccess")){
                ret.append(s).append(child.get("value")).append(";");
            }
        }

        s += ret;

        return s;

    }


    private String dealWithImports(JmmNode jmmNode, String s) {
        s = s != null ? s : "";
        StringBuilder ret = new StringBuilder();

        while (jmmNode.getNumChildren() != 0){
            jmmNode = jmmNode.getJmmChild(0);
            ret.insert(0, jmmNode.get("value") + ".");
        }
        ret.deleteCharAt(ret.length()-1);
        ret.insert(0, s + "import ");
        ret.append(";\n");

        this.table.addImports(ret.toString());

        return ret.toString();
    }

    private String dealWithClassDeclaration(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");
        ret.append("\n");

        for (JmmNode child : jmmNode.getChildren()) {
            ret.append(visit(child, "")).append(" ");
        }
        ret.append("{");

        return ret.toString();
    }

    private String dealWithClassName(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");
        String class_name = jmmNode.get("value");

        ret.append("Class ").append(class_name);
        table.setClassName(class_name);

        return ret.toString();
    }


    private String dealWithSuper(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");
        String sup = jmmNode.get("value");

        ret.append("extends ").append(sup);
        table.setSuper(sup);

        return ret.toString();
    }

    private String dealWithImplemented(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");

        String imp = jmmNode.get("value");
        ret.append("implements ").append(imp);

        return ret.toString();
    }

    private String dealWithClassBody(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder(s != null ? s : "");
        for (JmmNode child : jmmNode.getChildren()) {
            ret.append(visit(child, "\n\t"));
        }
        s = ret.toString();

        return s;
    }

    private String dealWithFields(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder();
        JmmNode declaration = jmmNode.getJmmChild(0);
        boolean isArray = Objects.equals(declaration.getJmmChild(0).getKind(), "ArrayType");

        String type_name = declaration.getJmmChild(0).get("type");
        Type type = new Type(type_name, isArray);

        String var = jmmNode.getJmmChild(0).get("var");
        Symbol field = new Symbol(type, var);

        ret.append(s).append(type_name);
        if(isArray) ret.append("[]");
        ret.append(" ").append(var).append(";");

        table.addFields(field);

        return ret.toString();
    }

    private String dealWithMethods(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder();
        List<Symbol> parameters = new ArrayList<>();
        ret.append(s).append(s);

        table.addMethods(jmmNode.get("name"));

        for (JmmNode child : jmmNode.getChildren()) {
            switch (child.getKind()) {
                case "MethodCalls":
                    ret.append(this.dealWithMethodCalls(child, s));
                    break;

                case "Modifier":
                    ret.append(child.get("value")).append(" ");
                    break;
                case "Type":
                    ret.append(child.get("type")).append(" ").append(jmmNode.get("name")).append("(");
                    table.addReturnType(jmmNode.get("name"), new Type(child.get("type"), false));
                    break;
                case "ArrayType":
                    ret.append(child.get("type")).append("[] ").append(jmmNode.get("name")).append("(");
                    table.addReturnType(jmmNode.get("name"), new Type(child.get("type"), true));
                    break;
                case "Argument":
                    if (Objects.equals(child.getJmmChild(0).getKind(), "Type")){
                        ret.append(child.getJmmChild(0).get("type")).append(" ").append(child.get("var")).append(", ");
                        parameters.add(new Symbol(new Type(child.getJmmChild(0).get("type"), false), child.get("var")));
                    }
                    else if (Objects.equals(child.getJmmChild(0).getKind(), "ArrayType")){
                        ret.append(child.getJmmChild(0).get("type")).append("[] ").append(child.get("var")).append(", ");
                        parameters.add(new Symbol(new Type(child.getJmmChild(0).get("type"), true), child.get("var")));
                    }
                    break;
                case "MethodBody":
                    if(parameters.size() != 0) ret.setLength(ret.length()-2);
                    ret.append(") {");
                    ret.append(visit(child, "\n\t\t"));
                    ret.append(s).append("}");

                    break;
                default:
                    break;
            }
        }

        table.setParameters(jmmNode.get("name"), parameters);

        return ret.toString();
    }

    public String dealWithMethodCalls(JmmNode jmmNode, String s){

        String ret = "";

        for (JmmNode child : jmmNode.getChildren()){

            if (Objects.equals(child.getKind(), "Self")){
                ret = jmmNode.getJmmChild(0).get("value");
            }

        }

        s += ret;

        return s;
    }


    private String dealWithMethodBody(JmmNode jmmNode, String s) {
        StringBuilder ret = new StringBuilder();
        List<Symbol> localVars = new ArrayList<>();

        for (JmmNode child : jmmNode.getChildren()) {
            if(Objects.equals(child.getKind(), "Return")) ret.append(this.dealWithReturn(child, s));
            if(!Objects.equals(child.getKind(), "Declaration")) continue;

            if (Objects.equals(child.getJmmChild(0).getKind(), "Type")){
                ret.append(s).append(child.getJmmChild(0).get("type")).append(" ").append(child.get("var")).append(";");
                localVars.add(new Symbol(new Type(child.getJmmChild(0).get("type"), false), child.get("var")));
            }
            else if (Objects.equals(child.getJmmChild(0).getKind(), "ArrayType")){
                ret.append(s).append(child.getJmmChild(0).get("type")).append("[] ").append(child.get("var")).append(";");
                localVars.add(new Symbol(new Type(child.getJmmChild(0).get("type"), true), child.get("var")));
            }
        }

        table.setLocalVariables(jmmNode.getJmmParent().get("name"), localVars);

        return ret.toString();
    }
}
