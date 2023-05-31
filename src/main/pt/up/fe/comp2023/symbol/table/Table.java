package pt.up.fe.comp2023.symbol.table;

import pt.up.fe.comp.jmm.analysis.table.Symbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Table implements SymbolTable {

    List<String> imports, methods;
    HashMap<String, Type> methodRet;
    HashMap<String, List<Symbol>> parameters, local_var;
    List<Symbol> fields;
    String class_name, super_class;

    int b;

    public Table(){
        this.imports = new ArrayList<>();
        this.methods = new ArrayList<>();
        this.fields = new ArrayList<>();
        this.parameters = new HashMap<>();
        this.local_var = new HashMap<>();
        this.class_name = "";
        this.super_class = "";
        this.methodRet = new HashMap<>();
        this.b = 1;
    }

    public void addImports(String imports) {
        this.imports.add(imports);
    }

    @Override
    public List<String> getImports() {
        return imports;
    }

    public void setClassName(String class_name) {
        this.class_name = class_name;
    }

    public String getClassName() {
        return class_name;
    }

    public void setSuper(String superClass){
        this.super_class = superClass;
    }

    public String getSuper() {
        return super_class;
    }

    public void setFields(List<Symbol> fields) {
        this.fields = fields;
    }

    public void addFields(Symbol field){this.fields.add(field);}

    @Override
    public List<Symbol> getFields() {
        return fields;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public void addMethods(String method) {this.methods.add(method);}

    @Override
    public List<String> getMethods() {
        return methods;
    }

    public void addReturnType(String methodSignature, Type ret_type) {
        this.methodRet.put(methodSignature, ret_type);
    }

    @Override
    public Type getReturnType(String methodSignature) {
        return methodRet.get(methodSignature);
    }

    public void setParameters(String methodSignature, List<Symbol> parameters) {
        this.parameters.put(methodSignature, parameters);
    }

    @Override
    public List<Symbol> getParameters(String methodSignature) {
        return parameters.get(methodSignature);
    }

    public void setLocalVariables(String methodSignature,List<Symbol> local_var) {
        this.local_var.put(methodSignature, local_var);
    }

    @Override
    public List<Symbol> getLocalVariables(String methodSignature){
        return local_var.get(methodSignature);
    }
}
