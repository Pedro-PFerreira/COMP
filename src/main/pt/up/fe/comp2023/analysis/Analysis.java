package pt.up.fe.comp2023.analysis;
import pt.up.fe.comp.jmm.analysis.JmmAnalysis;
import pt.up.fe.comp.jmm.analysis.JmmSemanticsResult;
import pt.up.fe.comp.jmm.analysis.table.Symbol;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.parser.JmmParserResult;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.ReportType;
import pt.up.fe.comp.jmm.report.Stage;
import java.util.*;
import pt.up.fe.comp2023.symbol.table.Table;
import pt.up.fe.comp2023.symbol.table.TableVisitor;


public class Analysis implements JmmAnalysis {

    Table table;
    //Recalculated for Every Method:
    List<Symbol> vars; //Relevant Vars for the current method
    Boolean isMethodStatic, isMethodPrivate;
    String methodName, returnType;

    private boolean isField(String name){
        for(Symbol field : table.getFields()){
            if(field.getName() == name){
                return true;
            }
        }
        return false;
    }

    /**
     * Receives a node of kind MethodCall and returns a list with the types of each of the given arguments
     * @param methodCall
     * @return
     */
    private List<String> getTypeOfArgs(JmmNode methodCall){
        List<String> res = new ArrayList<>();
        for(JmmNode child : methodCall.getChildren()){
            switch (child.getJmmChild(0).getKind()) {
                case "Integer" -> res.add("int");
                case "Boolean" -> res.add("boolean");
                case "Identifier" -> res.add(getVarType(child.getJmmChild(0).get("value")));
                case "MethodCalls" -> res.add(getMethodCallType(child));
            }
        }
        return res;
    }

    /**
     * Updates List of Relevant Vars for the method currently being Analyzed
     */
    private void updateRelevantVars() {
        vars = table.getLocalVariables(methodName);
        vars.addAll(table.getParameters(methodName));
        vars.addAll(table.getFields());
    }

    /**
     * Creates a new object Report
     * @param node Node where the error was detected
     * @param message Error Message
     * @return
     */
    private Report createReport(JmmNode node, String message) {
        int startLine = Integer.parseInt(node.get("lineStart")), startColumn = Integer.parseInt(node.get("colStart"));
        return new Report(ReportType.ERROR, Stage.SEMANTIC, startLine, startColumn, message);
    }

    /**
     * Erases Duplicates in a List of Reports
     * @param reports List with duplicates
     * @return List without duplicates
     */
    private List<Report> eraseDuplicateReports(List<Report> reports) {
        Set<Report> set = new HashSet<>(reports);
        return new ArrayList<>(set);
    }

    /**
     * Checks if a given class is imported in the file
     * @param className Class we desire to check
     * @param imports List of imports of the file
     * @return
     */
    private Boolean isContainedInImports(String className, List<String> imports) {
        if(imports.size() == 0) return false;
        for (String imported : imports){
            if (("import " + className + ";\n").equals(imported)){
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a given variable is an Array or not in the context of the current method
     * @param varName Name of the variable to check
     * @return true if is an array or false if it is not an array or is not declared
     */
    private Boolean isVarArray(String varName) {
        for (Symbol var : vars) {
            if (Objects.equals(var.getName(), varName)) {
                if (var.getType().isArray())
                    return true;
            }
        }
        return false;
    }

    /**
     * Get a Variable's Type in the context of the current method
     * @param varName Variable's Name
     * @return null if not declared, String with the name of the type if otherwise
     */
    private String getVarType(String varName) {
        for (Symbol var : vars) {
            if (Objects.equals(var.getName(), varName)) {
                if (var.getType().isArray() && !Objects.equals(var.getType().getName(), "String"))
                    return var.getType().getName() + "[]";
                else
                    return var.getType().getName();
            }
        }
        return null;
    }

    /**
     * Returns the Type of a Binary Operation
     * @param node BinaryOp node to analyze
     * @return "invalid_type" if there are errors inside the operation, type of the operation otherwise
     */
    private String getTypeOfBinaryOp(JmmNode node) {
        JmmNode first = node.getChildren().get(0);
        JmmNode second = node.getChildren().get(1);
        String firstType, secondType, result;
        //Evaluate the type of the left operand
        firstType = switch (first.getKind()) {
            case "Identifier" -> getVarType(first.get("value"));
            case "Boolean" -> "boolean";
            case "Integer" -> "int";
            case "BinaryOp" -> getTypeOfBinaryOp(first);

            default -> "invalid_type";
        };
        //Evaluate the type of the right operand
        secondType = switch (second.getKind()) {
            case "Identifier" -> getVarType(second.get("value"));
            case "Boolean" -> "boolean";
            case "Integer" -> "int";
            case "BinaryOp" -> getTypeOfBinaryOp(second);
            default -> "invalid_type";
        };
        switch (node.get("op")) {
            case ">":
            case "<":
            case "!=":
            case ">=":
            case "<=":
                if (!Objects.equals(firstType, "int") || !Objects.equals(secondType, "int"))
                    result = "invalid_type";
                else result = "boolean";
                break;
            case "+":
            case "-":
            case "*":
            case "/":
            case "%":
            case "&":
            case "|":
            case "^":
                if (!Objects.equals(firstType, "int") || !Objects.equals(secondType, "int"))
                    result = "invalid_type";
                else result = "int";
                break;
            case "==":
                if (!Objects.equals(firstType, secondType) && ((!Objects.equals(firstType, table.getClassName()) && !Objects.equals(secondType, table.getSuper())) || (!Objects.equals(secondType, table.getClassName()) && !Objects.equals(firstType, table.getSuper()))))
                    result = "invalid_type";
                else result = "boolean";
            case "&&":
            case "||":
                if (!Objects.equals(firstType, "boolean") || !Objects.equals(secondType, "boolean")) result = "invalid";
                else result = "boolean";
                break;
            default:
                result = "invalid_type";
        }
        return result;
    }

    /**
     * Checks for errors inside a Node of kind BinaryOp
     * @param reports List of reports registered so far in the analysis
     * @param node BinaryOp node to be checked
     * @return Updated list of reports with possible new errors detected inside this node
     */
    private List<Report> visitBinaryOp(List<Report> reports, JmmNode node) {
        String operator = node.get("op");
        JmmNode first = node.getChildren().get(0), second = node.getChildren().get(1);
        String firstType, secondType;
        switch (first.getKind()) {
            case "Identifier" -> {
                firstType = getVarType(first.get("value"));
                reports.addAll(visitIdentifier(reports, first, firstType));
            }
            case "Boolean" -> firstType = "boolean";
            case "Integer" -> firstType = "int";
            case "BinaryOp" -> {
                firstType = getTypeOfBinaryOp(first);
                reports.addAll(visitBinaryOp(reports, first));
            }
            case "ArrayAccess" ->{
                firstType = getArrayAccessReturn(first);
                reports.addAll(visitArrayAccess(reports,first));
            }
            default -> {
                firstType = "invalid_type";
            }
        }
        switch (second.getKind()) {
            case "Identifier" -> {
                secondType = getVarType(second.get("value"));
                reports.addAll(visitIdentifier(reports, second, secondType));
            }
            case "Boolean" -> secondType = "boolean";
            case "Integer" -> secondType = "int";
            case "BinaryOp" -> {
                secondType = getTypeOfBinaryOp(second);
                reports.addAll(visitBinaryOp(reports, second));
            }
            case "ArrayAccess" ->{
                secondType = getArrayAccessReturn(second);
                reports.addAll(visitArrayAccess(reports,second));
            }
            default -> {
                secondType = "invalid_type";
            }
        }
        switch (operator) {
            //Comparators
            case ">":
            case "<":
            case "==":
            case "!=":
            case ">=":
            case "<=":
                //Arithmetic Operators
            case "+":
            case "-":
            case "*":
            case "/":
            case "%":
                //If Type of one of the operands is invalid, the problem is already reported
                if (!Objects.equals(firstType, "invalid_type") && !Objects.equals(secondType, "invalid_type")) {
                    if (!Objects.equals(firstType, "int") || !Objects.equals(secondType, "int")) {
                        reports.add(createReport(node, "Cannot use '" + operator + "' between '" + firstType + "' and '" + secondType + "'."));
                    }
                }
                break;
            //Logic Operators (Can only be used between booleans)
            case "&&":
            case "||":
                if (!Objects.equals(firstType, "boolean") || !Objects.equals(secondType, "boolean")) {
                    reports.add(createReport(node, "Cannot use '" + operator + "' between '" + firstType + "' and '" + secondType + "'. Both should be 'boolean'."));
                }
                break;
                //Bitwise Operators (Can only be used between ints)
            case "&":
            case "|":
            case "^":
                if (!Objects.equals(firstType, "int") || !Objects.equals(secondType, "int")) {
                    reports.add(createReport(node, "Cannot use '" + operator + "' between '" + firstType + "' and '" + secondType + "'. Both should be 'int'."));
                }
                break;
            default:
                break;
        }
        return reports;
    }

    /**
     * Checks a JmmNode of Kind Return for errors
     * @param reports List of Reports regstered so far in the analysis
     * @param root Return Node to be checked
     * @return Updated List of Reports with possible new errors detected inside this node
     */
    private List<Report> visitReturn(List<Report> reports, JmmNode root) {
        JmmNode child = root.getChildren().get(0);
        updateRelevantVars();
        switch (child.getKind()) {
            case "Integer":
                if (!Objects.equals(returnType, "int")) {
                    reports.add(createReport(child, "Method " + methodName + " should return " + returnType + " but is returning 'int'."));
                }
                break;
            case "Boolean":
                if (!Objects.equals(returnType, "boolean")) {
                    reports.add(createReport(child, "Method " + methodName + " should return " + returnType + " but is returning 'boolean'."));
                }
                break;
            case "Identifier":
                String varName = child.get("value");
                String varType = getVarType(varName);
                reports.addAll(visitIdentifier(reports, child, varType));
                break;
            case "BinaryOp":
                String operationType = getTypeOfBinaryOp(child);
                if (Objects.equals(operationType, "invalid_type")) {
                    reports.addAll(visitBinaryOp(reports, child));
                } else if (!Objects.equals(operationType, returnType)) {
                    reports.add(createReport(child, "Method " + methodName + " should return '" + returnType + "' but is returning '" + operationType + "'."));
                }
                break;
            case "MethodCalls":
                String calledMethodName = child.getJmmChild(1).get("methodName");
                String varCalledOver = child.getJmmChild(0).get("value");
                String varCalledOverType = getVarType(varCalledOver);
                if(Objects.equals(varCalledOverType, table.getClassName())){  //method of the same class
                    if(table.getMethods().contains(calledMethodName)){ //method is declared
                        String methodReturnType = table.getReturnType(calledMethodName).getName();
                        if (!Objects.equals(methodReturnType, returnType)){
                            reports.add(createReport(child, "Return type of " + methodName + " is '" + returnType + "' but " + calledMethodName + " returns '" + methodReturnType + "'."));
                        }
                    }
                }
                reports.addAll(visitMethodCalls(reports, child));
                break;
            case "ArrayAccess":
                reports.addAll(visitArrayAccess(reports, child));
                break;
            case "Self":
                if(isMethodStatic){
                    reports.add(createReport(child,"'this' cannot be used in a static method."));
                }
                else if(!Objects.equals(returnType, table.getClassName()) && !Objects.equals(returnType, table.getSuper())){
                    reports.add(createReport(child,"Returning '" + table.getClassName() + "' when expecting to return '" + returnType + "'."));
                }
                break;
        }
        return reports;
    }

    /**
     * Obtains return type of a method declared inside the class
     * @param node MethodCalls node
     * @return null if method is not of the current class, String representing return type otherwise
     */
    private String getMethodCallType(JmmNode node) {
        String methodClassName = getVarType(node.getChildren().get(0).get("value")); //class over which the method is called
        if (Objects.equals(table.getClassName(), methodClassName)) { //In case it is the class where the method is called
            return table.getReturnType(methodName).getName();
        } else return null;
    }

    /**
     * Get a Called Method Name
     * @param node MethodCalls node
     * @return String with the Method Name
     */
    private String getMethodCallName(JmmNode node) {
        return getVarType(node.getChildren().get(0).get("value"));
    }

    /**
     * Visits a MethodCalls node and checks it for errors
     * @param reports List of Reports of previously detected errors
     * @param root Node MethodCalls to check
     * @return Updated List of Reports
     */
    private List<Report> visitMethodCalls(List<Report> reports, JmmNode root) {
        JmmNode child = root.getJmmChild(0);
        String childKind = child.getKind();
        String calledMethodName = root.getJmmChild(1).get("methodName");
        String calledOver = root.getJmmChild(0).get("value");
        switch (childKind) {
            case "Identifier":
                //CHAMAR VISITINDENTIFIER AQUI?
                String className = table.getClassName();
                List<String> imports = table.getImports();
                if (getVarType(calledOver) == null){
                    if (!isContainedInImports(calledOver, imports) && !Objects.equals(calledOver, className)) {
                        reports.add(createReport(root, calledOver + " doesn't exist. Maybe you forgot to import a class or define a variable?"));
                    }
                }
                if (Objects.equals(getVarType(calledOver), className) && !table.getMethods().contains(calledMethodName) && Objects.equals(table.getSuper(), "")) {
                    reports.add(createReport(root, "Method " + calledMethodName + " is not declared."));
                }
                break;
            case "Self":
                if(isMethodStatic){
                    reports.add(createReport(child,"'this' cannot be used in a static method."));
                }
                else if(!table.getMethods().contains(calledMethodName) && Objects.equals(table.getSuper(),"")){
                    reports.add(createReport(child,"Method " + calledMethodName + " is not declared."));
                }
                break;
        }
        if (Objects.equals(getVarType(calledOver), table.getClassName()) && !table.getMethods().contains(calledMethodName) && Objects.equals(table.getSuper(), "")){
            reports.add(createReport(child,"Method " + calledMethodName + " is not declared."));
        }
        else if(Objects.equals(getVarType(calledOver), table.getClassName()) && table.getMethods().contains(calledMethodName)) {
            List<Symbol> expectedParameters = table.getParameters(calledMethodName);
            List<String> receivedTypes = getTypeOfArgs(root.getJmmChild(1));
            if (expectedParameters == null){
                if (receivedTypes.size() > 0) {
                    reports.add(createReport(root, calledMethodName + " doesn't take any arguments but received" + receivedTypes.size() + "."));
                }
            }
            else if (expectedParameters.size() != receivedTypes.size()) {
                reports.add(createReport(root, calledMethodName + " expected " + expectedParameters.size() + " arguments but received " + receivedTypes.size() + "."));
            } else {
                List<String> expectedTypes = new ArrayList<>();
                for (Symbol arg : expectedParameters) {
                    expectedTypes.add(arg.getType().getName());
                }
                int i = 0;
                while (i < expectedTypes.size()) {
                    String argName = expectedParameters.get(i).getName();
                    String expectedType = expectedParameters.get(i).getType().getName();
                    if (!Objects.equals(expectedType, receivedTypes.get(i))) {
                        reports.add(createReport(root, "Argument " + argName + " of " + calledMethodName + " expected a " + expectedType + " but received " + receivedTypes.get(i)));
                    }
                    i++;
                }
            }
        }
        return reports;
    }

    /**
     * Returns type of an array element
     * @param root ArrayAccess Node
     * @return Type of Array Element returned from Access
     */
    private String getArrayAccessReturn(JmmNode root) {
        JmmNode arrayChild = root.getJmmChild(0);
        for (Symbol var : vars) {
            if (Objects.equals(var.getName(), arrayChild.get("value"))) {
                return var.getType().getName();
            }
        }
        return null;
    }

    /**
     * Visits Assignment Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root Assignement Node
     * @return Updated List of Reports
     */
    private List<Report> visitAssignment(List<Report> reports, JmmNode root) {
        JmmNode child = root.getChildren().get(0);
        updateRelevantVars();
        String varType = getVarType(root.get("var"));
        String kind = child.getKind();
        switch (child.getKind()) {
            case "Modifier":
                break;
            case "NewObject":
                String assignType = child.getChildren().get(0).get("type");
                if (child.getChildren().size() == 2) assignType += "[]";
                if (varType == null) {
                    String message = "Variable " + root.get("var") + " does not exist.";
                    reports.add(createReport(root, message));
                } else if (!Objects.equals(varType, assignType)) {
                    if((!varType.equals(table.getClassName()) && !Objects.equals(assignType, table.getSuper())) && (!Objects.equals(assignType, table.getClassName()) && !varType.equals(table.getSuper()))) {
                        String message = "Assignment between a '" + varType + "' and a '" + assignType + "'.";
                        reports.add(createReport(root, message));
                    }
                }
                break;
            case "ArrayAccess":
                String arrayReturnType = getArrayAccessReturn(child);
                String arrayType = getVarType(child.getJmmChild(0).get("value"));
                if (!Objects.equals(arrayReturnType, varType)) {
                    reports.add(createReport(child, "Assigning variable of type '" + varType + "' to element of array of type '" + arrayType + "'."));
                }
                reports.addAll(visitArrayAccess(reports, child));
            case "Integer":
                assignType = "int";
                if (!Objects.equals(varType, assignType)) {
                    String message = "Assignment between a '" + varType + "' and a '" + assignType + "'.";
                    reports.add(createReport(root, message));
                }
                break;
            case "Boolean":
                assignType = "boolean";
                if (!Objects.equals(varType, assignType)) {
                    String message = "Assignment between a '" + varType + "' and a '" + assignType + "'.";
                    reports.add(createReport(root, message));
                }
                break;
            case "Identifier":
                String varName = root.get("var");
                String assignedVarType = getVarType(varName);
                reports.addAll(visitIdentifier(reports, child, assignedVarType));
                break;
            case "BinaryOp":
                String operationType = getTypeOfBinaryOp(child);
                if (Objects.equals(operationType, "invalid_type")) {
                    reports.addAll(visitBinaryOp(reports, child));
                } else if (!Objects.equals(operationType, varType)) {
                    reports.add(createReport(child, "Assignment between a '" + varType + "' and a '" + operationType + "'."));
                }
                break;
            case "MethodCalls":
                String calledOver = getVarType(child.getJmmChild(0).get("value"));
                if(Objects.equals(calledOver, table.getClassName())){ //se o método é chamado sobre objeto da própria classe
                    String declaredRet = getMethodCallType(child); //retorno da declaração do método
                    String expectedRet = getVarType(root.get("var")); //retorno esperado do método
                    if(!Objects.equals(declaredRet, expectedRet) && declaredRet != null){
                        reports.add(createReport(root,"Assignment between a '" + declaredRet + "' and '" + expectedRet + "'."));
                    }
                }
                reports.addAll(visitMethodCalls(reports, child));
                break;
            case "Self":
                if(isMethodStatic){
                    reports.add(createReport(child,"'this' cannot be used in a static method."));
                }
                else if(!Objects.equals(varType, table.getClassName()) && !Objects.equals(varType, table.getSuper())){
                    reports.add(createReport(child,"Assigning '" + table.getClassName() + "' to '" + varType + "'."));
                }
                break;
            default:
                break;
        }
        return reports;
    }
    /**
     * Visits Identifier Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root Identifier Node
     * @param varType Type the variable is being assigned to
     * @return Updated List of Reports
     */
    private List<Report> visitIdentifier(List<Report> reports, JmmNode root, String varType) {
        String idType = getVarType(root.get("value"));
        if (idType == null) { //Checks if variable was previously declared
            reports.add(createReport(root, "Variable " + root.get("value") + " is not declared."));
        } else if (isMethodStatic && isField(root.get("value"))){
            reports.add(createReport(root, "Cannot use fields in a static method."));
        }
        else {
            switch (root.getJmmParent().getKind()) {
                case "Assignment":
                    if (!Objects.equals(varType, idType)) {
                        if (!(Objects.equals(varType, table.getClassName()) && idType.equals(table.getSuper())) &&
                                !(Objects.equals(idType, table.getClassName()) && varType.equals(table.getSuper())) &&
                                (!isContainedInImports(idType,table.getImports()) || !isContainedInImports(varType,table.getImports())))
                            reports.add(createReport(root, "Assignment between a '" + varType + "' and a '" + idType + "'."));
                    }
                    break;
                default:
                    break;
            }
        }
        return reports;
    }

    /**
     * Visits Declaration Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root Declaration Node
     * @return Updated List of Reports
     */
    private List<Report> visitDeclaration(List<Report> reports, JmmNode root){
        String varName = root.get("var");
        String leftType = root.getJmmChild(0).get("type");
        updateRelevantVars();
        if (root.getChildren().size() == 2){
            String rightType;
            JmmNode assignNode = root.getJmmChild(1);
            switch(assignNode.getKind()){
                case "Integer":
                    rightType = "int";
                    break;
                case "Boolean":
                    rightType = "boolean";
                    break;
                case "Identifier":
                    rightType = getVarType(assignNode.get("value"));
                    if (rightType == null) rightType = "invalid_type";
                    reports.addAll(visitIdentifier(reports,assignNode,rightType));
                    break;
                case "BinaryOp":
                    rightType = getTypeOfBinaryOp(assignNode);
                    reports.addAll(visitBinaryOp(reports,assignNode));
                    break;
            }
        }
        return reports;
    }
    /**
     * Visits MethodBody Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param node MethodBody Node
     * @return Updated List of Reports
     */
    private List<Report> visitMethodBody(List<Report> reports, JmmNode node){
        updateRelevantVars();
        for(JmmNode child : node.getChildren()){
            switch(child.getKind()) {
                case "Assignment":
                    reports.addAll(visitAssignment(reports, child));
                    break;
                case "Declaration":
                    reports.addAll(visitDeclaration(reports,child));
                    break;
                case "MethodCalls":
                    visitMethodCalls(reports,child);
                    break;
                case "Return":
                    reports.addAll(visitReturn(reports, child));
                    break;
                case "IfElse":
                    reports.addAll(visitIfElse(reports,child));
                    break;
                case "ExprStmt":
                    reports.addAll(visitExprStmt(reports,child));
                    break;
                case "While":
                    reports.addAll(visitWhile(reports,child));
                    break;
                default:
                    break;
            }
        }
        return reports;
    }
    /**
     * Visits While Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root While Node
     * @return Updated List of Reports
     */
    private Collection<? extends Report> visitWhile(List<Report> reports, JmmNode root) {
        JmmNode condition = root.getJmmChild(0);
        updateRelevantVars();
        switch(condition.getKind()){
            case "Identifier":
                String varName = condition.get("value");
                String varType = getVarType(varName);
                if(!Objects.equals(varType, "boolean"))
                    reports.add(createReport(condition,"Condition should be 'boolean'."));

        }
        reports.addAll(visitMethodBody(reports,root.getJmmChild(1)));
        return reports;
    }
    /**
     * Visits ArrayAccess Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root ArrayAccess Node
     * @return Updated List of Reports
     */
    private List<Report> visitArrayAccess(List<Report> reports, JmmNode root){
        updateRelevantVars();
        JmmNode varChild = root.getChildren().get(0);
        JmmNode indexChild = root.getChildren().get(1);
        String varName = varChild.get("value");
        if (getVarType(varName) == null){
            reports.add(createReport(varChild,"Variable " + varName + " was not declared."));
        }
        else if (!isVarArray(varName)){
            reports.add(createReport(varChild,"Array Access over variable " + varName + " which is not an array."));
        }
        switch(indexChild.getKind()){
            case "Boolean":
                reports.add(createReport(indexChild,"Array Access Index should be of type 'int'."));
                break;
            case "MethodCalls":
                reports.addAll(visitMethodCalls(reports,indexChild));
                String calledOver = indexChild.getJmmChild(0).get("value");
                String calledOverType = getVarType(calledOver);
                String calledMethod = indexChild.getJmmChild(1).get("methodName");
                String methodReturn = table.getReturnType(calledMethod).getName();
                if (!Objects.equals(methodReturn, "int") && Objects.equals(calledOverType, table.getClassName()) && Objects.equals(table.getSuper(), ""));
                    reports.add(createReport(indexChild,"Array Access Index should be of type 'int'."));
                break;
            case "Integer":
                break;
            case "Identifier":
                String accessVarName = indexChild.get("value");
                String accessVarType = getVarType(accessVarName);
                if (!Objects.equals(accessVarType, "int")){
                    reports.add(createReport(indexChild,"Array Access Index should be of type 'int'."));
                }
                else {
                    reports.addAll(visitIdentifier(reports, indexChild, getVarType(accessVarName)));
                }
        }
        return reports;
    }
    /**
     * Visits ExprStmt Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root ExprStmt Node
     * @return Updated List of Reports
     */
    private List<Report> visitExprStmt(List<Report> reports, JmmNode root){
        updateRelevantVars();
        JmmNode child = root.getChildren().get(0);
        switch(child.getKind()){
            case "BinaryOp":
                reports.addAll(visitBinaryOp(reports,child));
                break;
            case "ArrayAccess":
                reports.addAll(visitArrayAccess(reports,child));
                break;
            case "MethodCalls":
                reports.addAll(visitMethodCalls(reports,child));
        }
        return reports;
    }
    /**
     * Visits Method Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param root Method Node
     * @return Updated List of Reports
     */
    private List<Report> visitMethod(List<Report> reports, JmmNode root){
        Queue<JmmNode> queue = new LinkedList<>();
        queue.addAll(root.getChildren());
        methodName = root.get("name");
        updateRelevantVars();
        isMethodPrivate = Boolean.FALSE;
        isMethodStatic = Boolean.FALSE;
        while (queue.size() > 0){
                JmmNode node = queue.remove();
                String kind = node.getKind();
                switch(kind){
                    case "Argument":
                        break;
                    case "MethodBody":
                        reports.addAll(visitMethodBody(reports,node));
                        break;
                    case "Modifier":
                        switch(node.get("value")){
                            case "private":
                                isMethodPrivate = Boolean.TRUE;
                                break;
                            case "static":
                                isMethodStatic = Boolean.TRUE;
                                break;
                            default:
                                break;
                        }
                        break;
                    case "Type":
                        returnType = node.get("type");
                        break;
                    case "ExprStmt":
                        reports.addAll(visitExprStmt(reports,node));
                        break;
                    case "IfElse":
                        reports.addAll(visitIfElse(reports,node));
                    default:
                        break;
                    }
        }
        return reports;
    }
    /**
     * Visits IfElse Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param node IfElse Node
     * @return Updated List of Reports
     */
    private List<Report> visitIfElse(List<Report> reports, JmmNode node) {
        JmmNode condition = node.getJmmChild(0), ifNode = node.getJmmChild(1), elseNode = null;
        if(node.getChildren().size() == 3) elseNode = node.getJmmChild(2);
        String conditionType;
        switch(condition.getKind()){
            case "Boolean":
                break;
            case "BinaryOp":
                conditionType = getTypeOfBinaryOp(condition);
                if (!Objects.equals(conditionType, "boolean") && conditionType != "invalid_type")
                    reports.add(createReport(condition,"Expected a 'boolean' inside If condition but received a '" + conditionType + "'."));
                else if (conditionType == "invalid_type")
                    reports.addAll(visitBinaryOp(reports,condition));
                break;
            case "Identifier":
                conditionType = getVarType(node.getJmmChild(0).get("value"));
                if (!Objects.equals(conditionType, "boolean") && conditionType != "invalid_type")
                    reports.add(createReport(condition,"Expected a 'boolean' inside If condition but received a '" + conditionType + "'."));
                break;
            default:
                reports.add(createReport(condition,"Expected a 'boolean' inside If Condition."));
                break;
        }
        reports.addAll(visitMethodBody(reports,ifNode));
        if (node.getChildren().size() == 3) reports.addAll(visitMethodBody(reports,elseNode));
        return reports;
    }
    /**
     * Visits Program Node and checks for errors
     * @param reports List of Reports of previosuly found errors
     * @param node Program Node
     * @return Updated List of Reports
     */
    private List<Report> visitProgram(List<Report> reports, JmmNode node){
        Queue<JmmNode> queue = new LinkedList<>();
        queue.add(node);
        while (queue.size() > 0){
            node = queue.remove();
            //Se for um dos abaixo, explorar os nós abaixo deles
            switch(node.getKind()){
                case "Program":
                case "ClassDeclaration":
                case "ClassName":
                case "ClassBody":
                    queue.addAll(node.getChildren());
                    break;
                case "ClassMethod":
                    reports.addAll(visitMethod(reports, node));
                    break;
                case "ImportPackage":
                    break;
                default:
                    queue.addAll(node.getChildren());
                    break;
            }
            //Se for um dos abaixo, ignorar
        }
        return reports;
    }

    /**
     * Detects Semantic Errors on the Parsed Code
     * @param parserResult Parsed Code
     * @return Results of Semantic Analysis
     */
    public JmmSemanticsResult semanticAnalysis(JmmParserResult parserResult){

        table = new Table();

        TableVisitor visitor = new TableVisitor(table);
        visitor.visit(parserResult.getRootNode(),"");

        //New Code Below:
        JmmNode root = parserResult.getRootNode();
        List<Report> reports = new ArrayList<Report>();
        reports = eraseDuplicateReports(visitProgram(reports,root));
        JmmSemanticsResult res = new JmmSemanticsResult(parserResult, visitor.getTable(), reports);
        System.out.println("DETECTED ERRORS:");
        for(Report r : reports){
            System.out.println("Error (Line " + r.getLine() + "):" + r.getMessage());
        }
        return res;
    }
}

