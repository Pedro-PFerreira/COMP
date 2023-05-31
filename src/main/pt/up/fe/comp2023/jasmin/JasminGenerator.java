package pt.up.fe.comp2023.jasmin;

import org.specs.comp.ollir.*;
import pt.up.fe.comp.jmm.jasmin.JasminBackend;
import pt.up.fe.comp.jmm.jasmin.JasminResult;
import pt.up.fe.comp.jmm.ollir.OllirResult;
import pt.up.fe.comp.jmm.report.Report;

import java.util.*;

public class JasminGenerator implements JasminBackend {
    private final static String labelPrefix = "line";
    private StringBuilder jasminCode;
    private ClassUnit classUnit;
    private HashMap<String, Descriptor> vars;
    private List<String> labels;
    private Stack<Instruction> contextStack;
    private int stackLimit;
    private int currentStack;
    private int numLines;

    @Override
    public JasminResult toJasmin(OllirResult ollirResult) {
        this.classUnit = ollirResult.getOllirClass();

        try {
            this.classUnit.checkMethodLabels();
            this.classUnit.buildCFGs();
            this.classUnit.buildVarTables();
        } catch (OllirErrorException e) {
            return null;
        }

        List<Report> reports = ollirResult.getReports();
        this.jasminCode = new StringBuilder();
        this.vars = new HashMap<>();
        this.labels = new ArrayList<>();
        this.contextStack = new Stack<>();
        this.stackLimit = 0;
        this.currentStack = 0;
        this.numLines = 0;

        // Add class declaration
        this.addLine(getClassDeclaration());

        // Add superclass
        this.addLine(getSuperclass());

        // Add fields
        this.addEmptyLine();
        for (Field field : this.classUnit.getFields()) {
            this.addLine(getField(field));
        }

        // Add methods
        for (Method method : this.classUnit.getMethods()) {
            this.vars = method.getVarTable();

            this.addEmptyLine();
            if (method.isConstructMethod()) {
                dealWithConstructorMethod();
            } else {
                dealWithMethod(method);
            }
        }

        return new JasminResult(ollirResult, this.jasminCode.toString(), reports);
    }

    private StringBuilder getClassDeclaration(){
        StringBuilder s = new StringBuilder();

        s.append(".class ");
        switch (this.classUnit.getClassAccessModifier()) {
            case PUBLIC -> s.append("public");
            case PRIVATE -> s.append("private");
            case PROTECTED -> s.append("protected");
        }
        if (this.classUnit.getPackage() != null) s.append(" ").append(this.classUnit.getPackage());
        s.append(this.classUnit.getClassName());

        return s;
    }

    private StringBuilder getSuperclass(){
        StringBuilder s = new StringBuilder();

        s.append(".super ");
        s.append(Objects.requireNonNullElse(this.classUnit.getSuperClass(), "java/lang/Object"));

        return s;
    }

    private StringBuilder getField(Field field){
        StringBuilder s = new StringBuilder();

        s.append(".field ");
        switch (field.getFieldAccessModifier()) {
            case PUBLIC -> s.append("public ");
            case PRIVATE -> s.append("private ");
            case PROTECTED -> s.append("protected ");
        }
        if (field.isStaticField()) s.append("static ");
        if (field.isFinalField()) s.append("final ");
        s.append(field.getFieldName());
        s.append(" ").append(getType(field.getFieldType()));
        if (field.isInitialized()) s.append(" = ").append(field.getInitialValue());

        return s;
    }

    private void dealWithConstructorMethod(){
        this.addLine(".method public <init>()V");
        this.addLine("\taload_0");
        this.addLine("\tinvokespecial " + Objects.requireNonNullElse(this.classUnit.getSuperClass(), "java/lang/Object") + ".<init>()V");
        this.addLine("\t return");
        this.addLine(".end method");
    }

    private void dealWithMethod(Method method){
        // Add method header
        this.addLine(getMethodHeader(method));

        // Add method body
        StringBuilder methodBody = new StringBuilder();
        StringBuilder sub = this.setStringBuilder(methodBody);

        this.stackLimit = 0;
        for(Instruction instruction : method.getInstructions()){
            this.currentStack = 0;
            this.labels = method.getLabels(instruction);

            for (String label : this.labels) {
                this.addLine(label + ":");
            }

            this.dealWithInstruction(instruction, "\t");
            this.updateStack(0);
        }

        this.setStringBuilder(sub);

        this.addLine("\t.limit stack " + this.stackLimit);
        this.addLine("\t.limit locals " + (this.vars.size() + (method.getVarTable().containsKey("this") || method.isStaticMethod() ? 0 : 1)));
        this.jasminCode.append(methodBody);
        this.addLine(".end method");
    }

    private StringBuilder getMethodHeader(Method method){
        StringBuilder s = new StringBuilder();

        s.append(".method ");
        switch (method.getMethodAccessModifier()) {
            case PUBLIC -> s.append("public ");
            case PRIVATE -> s.append("private ");
            case PROTECTED -> s.append("protected ");
        }
        if (method.isStaticMethod()) s.append("static ");
        if (method.isFinalMethod()) s.append("final ");
        s.append(method.getMethodName()).append("(");

        for(Element element : method.getParams()){
            s.append(getType(element.getType()));
        }

        s.append(")").append(getType(method.getReturnType()));

        return s;
    }

    private void dealWithInstruction(Instruction instruction, String tabs) {

        switch (instruction.getInstType()) {
            case ASSIGN -> dealWithAssign((AssignInstruction) instruction, tabs);
            case CALL -> dealWithCall((CallInstruction) instruction, tabs);
            case GOTO -> dealWithGoto((GotoInstruction) instruction, tabs);
            case BRANCH -> dealWithBranch((CondBranchInstruction) instruction, tabs);
            case RETURN -> dealWithReturn((ReturnInstruction) instruction, tabs);
            case PUTFIELD -> dealWithPutField((PutFieldInstruction) instruction, tabs);
            case GETFIELD -> dealWithGetField((GetFieldInstruction) instruction, tabs);
            case UNARYOPER -> dealWithUnaryOp((UnaryOpInstruction) instruction, tabs);
            case BINARYOPER -> dealWithBinaryOp((BinaryOpInstruction) instruction, tabs);
            case NOPER -> dealWithNoOp((SingleOpInstruction) instruction, tabs);
        }
    }

    private void dealWithAssign(AssignInstruction assignInstruction, String tabs) {
        Element dest = assignInstruction.getDest();
        String destName = ((Operand) dest).getName();
        Descriptor descriptor = this.vars.get(destName);

        if(dest.getClass().equals(ArrayOperand.class)){
            this.addLine(tabs + this.getLoadInstr("a", descriptor.getVirtualReg()));
            this.currentStack++;

            for(Element element : ((ArrayOperand) dest).getIndexOperands()){
                this.loadCallArg(element, tabs);
            }
        }
        else{
            if(checkAssignIINC(destName, descriptor.getVirtualReg(), assignInstruction.getRhs(), tabs)) return;
        }

        this.contextStack.push(assignInstruction);
        this.dealWithInstruction(assignInstruction.getRhs(), tabs);
        this.contextStack.pop();

        switch (dest.getType().getTypeOfElement()){
            case INT32, BOOLEAN -> {
                if(dest.getClass().equals(ArrayOperand.class)) this.addLine(tabs + "iastore");
                else this.addLine(tabs + this.getStoreInstr("i", descriptor.getVirtualReg()));
            }
            default -> {
                if(dest.getClass().equals(ArrayOperand.class)) this.addLine(tabs + "aastore");
                else this.addLine(tabs + this.getStoreInstr("a", descriptor.getVirtualReg()));
            }
        }
    }

    private void dealWithCall(CallInstruction callInstruction, String tabs) {
        StringBuilder s = new StringBuilder(tabs);

        switch (callInstruction.getInvocationType()) {
            case invokevirtual -> {
                s.append("invokevirtual ");
                this.loadCallArg(callInstruction.getFirstArg(), tabs);
                s.append(this.callArg(callInstruction.getFirstArg()));
            }
            case invokeinterface -> {
                s.append("invokeinterface ");
                this.loadCallArg(callInstruction.getFirstArg(), tabs);
                s.append(this.callArg(callInstruction.getFirstArg()));
            }
            case invokespecial -> {
                s.append("invokespecial ");
                s.append(this.callArg(callInstruction.getFirstArg()));
            }
            case invokestatic -> {
                s.append("invokestatic ");
                this.loadCallArg(callInstruction.getFirstArg(), tabs);
                s.append(this.callArg(callInstruction.getFirstArg()));
            }
            case NEW -> {
                if(this.callArg(callInstruction.getFirstArg()).equals("array")){
                    this.loadCallArg(callInstruction.getListOfOperands().get(0), tabs);
                    this.addLine(tabs + "newarray int");
                    this.updateStack(1);
                    this.currentStack++;
                    return;
                }
                else{
                    s.append("new ");
                    s.append(this.callArg(callInstruction.getFirstArg()));
                    this.currentStack++;
                }
            }
            case arraylength -> {
                this.loadCallArg(callInstruction.getFirstArg(), tabs);
                this.addLine(tabs + "arraylength");
                return;
            }
            case ldc -> this.loadCallArg(callInstruction.getFirstArg(), tabs);
        }

        if(callInstruction.getNumOperands() > 1){
            if(callInstruction.getInvocationType() != CallType.NEW){
                s.append(".").append(this.callArg(callInstruction.getSecondArg()));
            }

            this.contextStack.push(callInstruction);
            s.append("(");
            for(Element element : callInstruction.getListOfOperands()){
                this.loadCallArg(element, tabs);
                s.append(this.getType(element.getType()));
            }
            s.append(")");
            this.contextStack.pop();
        }

        String ret = getType(callInstruction.getReturnType());
        s.append(ret);
        this.addLine(s);

        if (this.contextStack.empty() && !ret.isEmpty() && !ret.equals("V")) {
            this.addLine(tabs + "pop");
            this.updateStack(1);
        }

        if (callInstruction.getInvocationType() == CallType.NEW) {
            this.addLine(tabs + "dup");
            this.currentStack++;
        }
    }

    private void dealWithGoto(GotoInstruction gotoInstruction, String tabs) {
        this.addLine(tabs + "goto " + gotoInstruction.getLabel());
    }

    private void dealWithBranch(CondBranchInstruction branchInstruction, String tabs) {
        if(branchInstruction.getCondition() instanceof BinaryOpInstruction opInstruction){
            Element leftElement = opInstruction.getLeftOperand();
            Element rightElement = opInstruction.getRightOperand();
            String label = branchInstruction.getLabel();

            switch (opInstruction.getOperation().getOpType()){
                case ANDB -> {
                    this.loadCallArg(leftElement, tabs);
                    this.addLine(tabs + "ifeq"  + label);
                    this.updateStack(1);
                    this.loadCallArg(rightElement, tabs);
                    this.addLine(tabs + "ifeq " + label);
                    this.updateStack(1);
                    this.currentStack++;
                }
                case ORB -> {
                    this.loadCallArg(leftElement, tabs);
                    this.addLine(tabs + "ifneq " + label);
                    this.updateStack(1);
                    this.loadCallArg(rightElement, tabs);
                    this.addLine(tabs + "ifneq " + label);
                    this.updateStack(1);
                    this.currentStack++;
                }
                case LTH -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmplt", "iflt", label, tabs);
                case GTH -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmpgt", "ifgt", label, tabs);
                case LTE -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmple", "ifle", label, tabs);
                case GTE -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmpge", "ifge", label, tabs);
                case EQ -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmpeq", "ifeq", label, tabs);
                case NEQ -> this.dealWithIntCmpBranch(leftElement, rightElement, "if_icmpne", "ifne", label, tabs);
            }
        }
        else if(branchInstruction.getCondition() instanceof SingleOpInstruction opInstruction){
            this.loadCallArg(opInstruction.getSingleOperand(), tabs);
            this.addLine(tabs + "ifne " + branchInstruction.getLabel());
            this.updateStack(1);
            this.currentStack++;
        }
    }

    private void dealWithReturn(ReturnInstruction returnInstruction, String tabs) {
        if (!returnInstruction.hasReturnValue()) {
            this.addLine(tabs + "return");
        }
        else{
            this.loadCallArg(returnInstruction.getOperand(), tabs);
            String retType;
            switch (returnInstruction.getOperand().getType().getTypeOfElement()){
                case INT32, BOOLEAN -> retType = "i";
                case VOID -> retType = "";
                default -> retType = "a";
            }
            this.addLine(tabs + retType + "return");
        }
    }

    private void dealWithPutField(PutFieldInstruction putFieldInstruction, String tabs) {
        Element e1 = putFieldInstruction.getFirstOperand();
        Element e2 = putFieldInstruction.getSecondOperand();
        Element e3 = putFieldInstruction.getThirdOperand();

        this.loadCallArg(e1, tabs);
        this.loadCallArg(e3, tabs);
        this.addLine(tabs + "putfield " + this.callArg(e1) + "." + this.callArg(e2) + " " + this.getType(e2.getType()));
    }

    private void dealWithGetField(GetFieldInstruction getFieldInstruction, String tabs) {
        Element e1 = getFieldInstruction.getFirstOperand();
        Element e2 = getFieldInstruction.getSecondOperand();

        this.loadCallArg(e1, tabs);
        this.addLine(tabs + "getfield " + this.callArg(e1) + "." + this.callArg(e2) + " " + this.getType(e2.getType()));
    }

    private void dealWithUnaryOp(UnaryOpInstruction opInstruction, String tabs) {
        this.contextStack.push(opInstruction);

        Element element = opInstruction.getOperand();
        Operation operation = opInstruction.getOperation();

        if(operation.getOpType() == OperationType.NOTB){
            if(element.isLiteral()){
                int literal = Integer.parseInt(this.callArg(element));
                this.addLine(tabs + this.boolLiteralPush(literal));
            }
            else{
                this.loadCallArg(element, tabs);
                String elseLabel = labelPrefix + (this.numLines + 4);
                String endLabel = labelPrefix + (this.numLines + 5);
                this.addLine(tabs + "ifne " + elseLabel);
                this.addLine(tabs + "iconst_1");
                this.addLine(tabs + "goto " + endLabel);
                this.addLine(elseLabel + ":");
                this.addLine(tabs + "iconst_0");
                this.addLine(endLabel + ":");
            }
            this.currentStack++;
        }
        this.contextStack.pop();
    }

    private void dealWithBinaryOp(BinaryOpInstruction opInstruction, String tabs) {
        this.contextStack.push(opInstruction);

        Element leftElement = opInstruction.getLeftOperand();
        Element rightElement = opInstruction.getRightOperand();

        switch (opInstruction.getOperation().getOpType()){
            case ADD -> this.dealWithIntArithmetic(leftElement, rightElement, "iadd", tabs);
            case SUB -> this.dealWithIntArithmetic(leftElement, rightElement, "isub", tabs);
            case MUL -> this.dealWithIntArithmetic(leftElement, rightElement, "imul", tabs);
            case DIV -> this.dealWithIntArithmetic(leftElement, rightElement, "idiv", tabs);
            case LTH -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmplt", tabs);
            case GTH -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmpgt", tabs);
            case LTE -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmple", tabs);
            case GTE -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmpge", tabs);
            case EQ -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmpeq", tabs);
            case NEQ -> this.dealWithBooleanArithmetic(leftElement, rightElement, "if_cmpne", tabs);
            case ANDB -> this.dealWithAndArithmetic(leftElement, rightElement, tabs);
        }

        this.contextStack.pop();
    }

    private void dealWithNoOp(SingleOpInstruction opInstruction, String tabs) {
        this.loadCallArg(opInstruction.getSingleOperand(), tabs);
    }

    private void dealWithIntCmpBranch(Element e1, Element e2, String op, String opZero, String label, String tabs){
        boolean zeroAtRight = e2.isLiteral() && this.callArg(e2).equals("0");

        this.loadCallArg(e1, tabs);
        if(zeroAtRight){
            this.addLine(tabs + opZero + " " + label);
            this.updateStack(1);
        }
        else{
            this.loadCallArg(e2, tabs);
            this.addLine(tabs + op + " " + label);
            this.updateStack(2);
        }
        this.currentStack++;
    }

    private void dealWithIntArithmetic(Element e1, Element e2, String op, String tabs){
        if(e1.isLiteral() && e2.isLiteral()){
            String lhs = this.callArg(e1);
            String rhs = this.callArg(e2);
            int n1 = Integer.parseInt(lhs);
            int n2 = Integer.parseInt(rhs);
            int result;
            switch (op){
                case "iadd" -> result = n1 + n2;
                case "isub" -> result = n1 - n2;
                case "imul" -> result = n1 * n2;
                case "idiv" -> result = n1 / n2;
                default -> {
                    return;
                }
            }

            this.addLine(tabs + this.intLiteralPush(result));
        }
        else {
            this.loadCallArg(e1, tabs);
            this.loadCallArg(e2, tabs);
            this.addLine(tabs + op);
            this.updateStack(2);
        }
        this.currentStack++;
    }

    private void dealWithBooleanArithmetic(Element e1, Element e2, String op, String tabs){
        this.loadCallArg(e1, tabs);
        this.loadCallArg(e2, tabs);

        String elseLabel = labelPrefix + (this.numLines + 4);
        String endLabel = labelPrefix + (this.numLines + 5);
        this.addLine(tabs + op + " " + elseLabel);
        this.addLine(tabs + "iconst_1");
        this.addLine(tabs + "goto " + endLabel);
        this.addLine(elseLabel + ":");
        this.addLine(tabs + "iconst_0");
        this.addLine(endLabel + ":");
        this.updateStack(2);
        this.currentStack++;
    }

    private void dealWithAndArithmetic(Element e1, Element e2, String tabs){
        String elseLabel = labelPrefix + (this.numLines + 7);
        String endLabel = labelPrefix + (this.numLines + 8);

        this.loadCallArg(e1, tabs);
        this.addLine(tabs + "ifeq " + elseLabel);
        this.updateStack(1);
        this.loadCallArg(e2, tabs);
        this.addLine(tabs + "ifeq " + elseLabel);
        this.updateStack(1);

        this.addLine(tabs + "iconst_1");
        this.addLine(tabs + "goto " + endLabel);
        this.addLine(elseLabel + ":");
        this.addLine(tabs + "iconst_0");
        this.addLine(endLabel + ":");
        this.currentStack++;
    }

    private String getLoadInstr(String pre, int vReg){
        if(vReg >= 0 && vReg <= 3) return pre + "load_" + vReg;
        else return pre + "load " + vReg;
    }

    private void loadCallArg(Element element, String tabs){
        String str = this.loadCallArg(element);

        if (str != null) {
            this.addLine(tabs + str);

            if (element.getClass().equals(ArrayOperand.class)) {
                for (Element e : ((ArrayOperand) element).getIndexOperands())
                    this.loadCallArg(e, tabs);

                switch (element.getType().getTypeOfElement()) {
                    case INT32, BOOLEAN -> this.addLine(tabs + "iaload");
                    default -> addLine(tabs + "aaload");
                }
            }
        }
    }

    private String loadCallArg(Element element){
        String ret;

        if (element.isLiteral()) {
            ret = this.loadCallArgLiteral((LiteralElement) element);
        } else {
            ret = this.loadCallArgOperand((Operand) element);
        }

        if (ret != null)
            this.currentStack++;

        return ret;
    }

    private String loadCallArgLiteral(LiteralElement literalElement) {
        String literal = literalElement.getLiteral();
        String result;

        switch (literalElement.getType().getTypeOfElement()) {
            case INT32 -> result = this.intLiteralPush(Integer.parseInt(literal));
            case BOOLEAN ->{
                if (literal.equals("1"))
                    result = "iconst_1";
                else
                    result = "iconst_0";
            }
            case OBJECTREF -> result = this.getLoadInstr("a", this.vars.get(literal).getVirtualReg());
            case STRING -> result = "ldc " + literal;
            default -> {return null;}
        }

        return result;
    }

    private String loadCallArgOperand(Operand op) {
        String name = op.getName();
        String result;

        switch (op.getType().getTypeOfElement()) {
            case INT32, BOOLEAN ->{
                String pre = "i";
                if (op.getClass().equals(ArrayOperand.class)) pre = "a";
                result = this.getLoadInstr(pre, this.vars.get(name).getVirtualReg());
            }
            case OBJECTREF, ARRAYREF, STRING -> result = this.getLoadInstr("a", this.vars.get(name).getVirtualReg());
            case THIS -> result = "aload_0";
            default -> {return null;}
        }

        return result;
    }

    private String getStoreInstr(String pre, int vReg) {
        if (vReg >= 0 && vReg <= 3)
            return pre + "store_" + vReg;
        else
            return pre + "store " + vReg;
    }

    private boolean checkAssignIINC(String destName, int reg, Instruction instr, String tabs) {
        if (instr.getInstType() != InstructionType.BINARYOPER)
            return false;

        BinaryOpInstruction binaryOpInstruction = (BinaryOpInstruction) instr;
        Operation op = binaryOpInstruction.getOperation();

        if (op.getOpType() != OperationType.ADD && op.getOpType() != OperationType.SUB)
            return false;

        Element leftElem = binaryOpInstruction.getLeftOperand();
        Element rightElem = binaryOpInstruction.getRightOperand();

        if (leftElem.isLiteral() && rightElem.isLiteral())
            return false;
        if (!leftElem.isLiteral() && !rightElem.isLiteral())
            return false;

        String usedName;
        int val;
        if (leftElem.isLiteral()) {
            if (op.getOpType() == OperationType.SUB)
                return false;

            val = Integer.parseInt(this.callArg(leftElem));
            usedName = this.callArg(rightElem);
        } else {
            val = Integer.parseInt(this.callArg(rightElem));
            usedName = this.callArg(leftElem);
        }

        if (!usedName.equals(destName))
            return false;

        if (op.getOpType() == OperationType.SUB)
            val *= -1;

        if (val == 0)
            return true;

        String res = this.iinc(reg, val);
        if (res == null)
            return false;

        this.addLine(tabs + res);
        return true;
    }

    private String callArg(Element e) {
        if (e.isLiteral()) {
            return ((LiteralElement) e).getLiteral().replace("\"", "");
        } else {
            Operand o = (Operand) e;
            Type t = o.getType();
            return switch (t.getTypeOfElement()) {
                case OBJECTREF -> ((ClassType) t).getName();
                case THIS -> this.classUnit.getClassName();
                default -> o.getName();
            };
        }
    }

    private String iinc(int reg, int i) {
        if (i >= -128 && i <= 127) {
            return "iinc " + reg + " " + i;
        } else if (i >= -32768 && i <= 32767) {
            return "iinc_w " + reg + " " + i;
        } else {
            return null;
        }
    }

    private String getType(Type type) {
        String s = "";
        switch (type.getTypeOfElement()) {
            case INT32 -> s = "I";
            case BOOLEAN -> s = "Z";
            case ARRAYREF -> {
                ArrayType arrayType = (ArrayType) type;
                s += "[" + getType(arrayType.getElementType());
            }
            case CLASS -> {
                ClassType classType = (ClassType) type;
                s = "L" + classType.getName() + ";";
            }
            case STRING -> s = "Ljava/lang/String;";
            case OBJECTREF -> s = "";
            case THIS -> s = this.classUnit.getClassName();
            case VOID -> s = "V";
        }
        return s;
    }

    private String intLiteralPush(int i) {
        if (i == -1) {
            return "iconst_m1";
        } else if (i >= 0 && i <= 5) {
            return "iconst_" + i;
        } else if (i >= -128 && i <= 127) {
            return "bipush " + i;
        } else if (i >= -32768 && i <= 32767) {
            return "sipush " + i;
        } else {
            return "ldc " + i;
        }
    }

    private String boolLiteralPush(boolean b) {
        if (b)
            return "iconst_1";
        else
            return "iconst_0";
    }

    private String boolLiteralPush(int i) {
        return this.boolLiteralPush(i == 0);
    }

    private void addLine(String code){
        this.jasminCode.append(code).append("\n");
        this.numLines++;
    }

    private void addLine(StringBuilder code){
        this.jasminCode.append(code).append("\n");
        this.numLines++;
    }

    private void addEmptyLine(){
        this.jasminCode.append("\n");
        this.numLines++;
    }

    private void updateStack(int n){
        if(this.currentStack > this.stackLimit){
            this.stackLimit = this.currentStack;
        }
        this.currentStack += n;
    }

    private StringBuilder setStringBuilder(StringBuilder s){
        StringBuilder prev = this.jasminCode;
        this.jasminCode = s;
        return prev;
    }
}
