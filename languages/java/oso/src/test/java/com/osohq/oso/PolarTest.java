package com.osohq.oso;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import org.json.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class PolarTest {
    protected Polar p;

    public static class MyClass {
        public String name;
        public Integer id;

        public MyClass(String name, Integer id) {
            this.name = name;
            this.id = id;
        }

        public String myMethod(String arg) {
            return arg;
        }

        public List<String> myList() {
            return List.of("hello", "world");
        }

        public MySubClass mySubClass(String name, Integer id) {
            return new MySubClass(name, id);
        }

        public Enumeration<String> myEnumeration() {
            return Collections.enumeration(List.of("hello", "world"));
        }

        public static String myStaticMethod() {
            return "hello world";
        }
    }

    public static class MySubClass extends MyClass {
        public MySubClass(String name, Integer id) {
            super(name, id);
        }
    }

    @BeforeEach
    public void setUp() {
        try {
            p = new Polar();
            p.registerClass(MyClass.class, m -> new MyClass((String) m.get("name"), (int) m.get("id")), "MyClass");
            p.registerClass(MySubClass.class, m -> new MySubClass((String) m.get("name"), (int) m.get("id")),
                    "MySubClass");
        } catch (Exceptions.OsoException e) {
            throw new Error(e);
        }
    }

    /**
     * Rigourous Test :-)
     */
    @Test
    public void testApp() {
        assertTrue(true);
    }

    /**** TEST QUERY ****/

    @Test
    public void testLoadAndQueryStr() throws Exception {
        p.loadStr("f(1);");
        Query query = p.queryStr("f(x)");
        assertEquals(List.of(Map.of("x", 1)), query.results());
    }

    @Test
    public void testInlineQueries() throws Exception {
        p.loadStr("f(1); ?= f(1);");
        assertThrows(Exceptions.InlineQueryFailedError.class, () -> p.loadStr("?= f(2);"),
                "Expected inline query to fail but it didn't.");
    }

    @Test
    public void testBasicQueryPred() throws Exception {
        // test basic query
        p.loadStr("f(a, b) if a = b;");
        assertFalse(p.queryPred("f", List.of(1, 1)).results().isEmpty(), "Basic predicate query failed.");
        assertTrue(p.queryPred("f", List.of(1, 2)).results().isEmpty(),
                "Basic predicate query expected to fail but didn't.");
    }

    @Test
    public void testQueryPredWithObject() throws Exception {
        // test query with Java Object
        p.loadStr("g(x) if x.id = 1;");
        assertFalse(p.queryPred("g", List.of(new MyClass("test", 1))).results().isEmpty(),
                "Predicate query with Java Object failed.");
        assertTrue(p.queryPred("g", List.of(new MyClass("test", 2))).results().isEmpty(),
                "Predicate query with Java Object expected to fail but didn't.");
    }

    @Test
    public void testQueryPredWithVariable() throws Exception {
        // test query with Variable
        p.loadStr("f(a, b) if a = b;");
        assertTrue(p.queryPred("f", List.of(1, new Variable("result"))).results().equals(List.of(Map.of("result", 1))),
                "Predicate query with Variable failed.");
    }

    /*** TEST FFI CONVERSIONS ***/

    @Test
    public void testBoolFFIRoundTrip() throws Exception {
        Boolean b = true;
        JSONObject polar = p.toPolarTerm(b);
        Object java = p.toJava(polar);
        assertEquals(b, java);
    }

    @Test
    public void testIntFFIRoundTrip() throws Exception {
        int i = 3;
        JSONObject polar = p.toPolarTerm(i);
        Object java = p.toJava(polar);
        assertEquals(i, java);
    }

    @Test
    public void testFloatFFIRoundTrip() throws Exception {
        float f = (float) 3.50;
        JSONObject polar = p.toPolarTerm(f);
        Object java = p.toJava(polar);
        assertEquals(f, java);
    }

    @Test
    public void testListFFIRoundTrip() throws Exception {
        List<Integer> l = List.of(1, 2, 3, 4);
        JSONObject polar = p.toPolarTerm(l);
        Object java = p.toJava(polar);
        assertEquals(l, java);
    }

    @Test
    public void testArrayFFIRoundTrip() throws Exception {
        int[] a1 = { 1, 2, 3, 4 };
        JSONObject polar = p.toPolarTerm(a1);
        Object java = p.toJava(polar);
        assertEquals(List.of(1, 2, 3, 4), java);

        double[] a2 = { 1.2, 3.5 };
        polar = p.toPolarTerm(a2);
        java = p.toJava(polar);

        // TODO: adjust so precision isn't lost going from double -> float
        assertEquals(List.of((float) 1.2, (float) 3.5), java);

        String[] a3 = { "hello", "world" };
        polar = p.toPolarTerm(a3);
        java = p.toJava(polar);
        assertEquals(List.of("hello", "world"), java);

    }

    @Test
    public void testDictFFIRoundTrip() throws Exception {
        Map<String, Integer> m = Map.of("a", 1, "b", 2);
        JSONObject polar = p.toPolarTerm(m);
        Object java = p.toJava(polar);
        assertEquals(m, java);
    }

    @Test
    public void testJavaClassFFIRoundTrip() throws Exception {
        MyClass instance = new MyClass("test", 1);
        JSONObject polar = p.toPolarTerm(instance);
        Object java = p.toJava(polar);
        assertEquals(instance, java);
    }

    @Test
    public void testPredicateFFIRoundTrip() throws Exception {
        Predicate pred = new Predicate("name", List.of(1, "hello"));
        JSONObject polar = p.toPolarTerm(pred);
        Object java = p.toJava(polar);
        assertEquals(pred, java);
    }

    /*** TEST EXTERNALS ***/

    @Test
    public void testRegisterAndMakeClass() throws Exception {
        Map<String, Object> testArg = Map.of("name", "testName", "id", 1);
        MyClass instance = (MyClass) p.makeInstance("MyClass", testArg, Long.valueOf(0));
        assertEquals("testName", instance.name);
        assertEquals(Integer.valueOf(1), instance.id);
        // TODO: test that errors when given invalid constructor
        // TODO: test that errors when registering same class twice
        // TODO: test that errors if same alias used twice
        // TODO: test inheritance
    }

    @Test
    public void testDuplicateRegistration() throws Exception {
        assertThrows(Exceptions.DuplicateClassAliasError.class, () -> p.registerClass(MyClass.class,
                m -> new MyClass((String) m.get("name"), (int) m.get("id")), "MyClass"));
    }

    @Test
    public void testMakeInstanceFromPolar() throws Exception {
        p.loadStr("f(x) if x = new MyClass{name: \"test\", id: 1};");
        Query query = p.queryStr("f(x)");
        MyClass ret = (MyClass) query.nextElement().get("x");
        assertEquals("test", ret.name);
        assertEquals(Integer.valueOf(1), ret.id);
    }

    @Test
    public void testRegisterCall() throws Exception {
        MyClass instance = new MyClass("test", 1);
        p.cacheInstance(instance, Long.valueOf(1));
        p.registerCall("myMethod", List.of("hello world"), 1, p.toPolarTerm(instance));
        JSONObject res = p.nextCallResult(1);
        assertTrue(p.toJava(res).equals("hello world"));
    }

    @Test
    public void testExternalCall() throws Exception {
        // Test get attribute
        p.loadStr("id(x) if x = new MyClass{name: \"test\", id: 1}.id;");
        assertTrue(p.queryStr("id(x)").results().equals(List.of(Map.of("x", 1))),
                "Failed to get attribute on external instance.");

        // Test call method
        p.loadStr("method(x) if x = new MyClass{name: \"test\", id: 1}.myMethod(\"hello world\");");
        assertTrue(p.queryStr("method(x)").results().equals(List.of(Map.of("x", "hello world"))),
                "Failed to get attribute on external instance.");
    }

    @Test
    public void testReturnJavaInstanceFromCall() throws Exception {
        MyClass c = new MyClass("test", 1);
        p.loadStr("test(c: MyClass) if x = c.mySubClass(c.name, c.id) and x.id = c.id;");
        assertFalse(p.queryPred("test", List.of(c)).results().isEmpty());
    }

    @Test
    public void testEnumerationCallResults() throws Exception {
        MyClass c = new MyClass("test", 1);
        p.loadStr("test(c: MyClass, x) if x = c.myEnumeration;");
        List<HashMap<String, Object>> results = p.queryPred("test", List.of(c, new Variable("x"))).results();
        assertTrue(results.equals(List.of(Map.of("x", "hello"), Map.of("x", "world"))));
    }

    @Test
    public void testStringMethods() throws Exception {
        p.loadStr("f(x) if x.length = 3;");
        assertFalse(p.queryStr("f(\"oso\")").results().isEmpty());
        assertTrue(p.queryStr("f(\"notoso\")").results().isEmpty());
    }

    @Test
    public void testListMethods() throws Exception {
        p.loadStr("f(x) if x.size() = 3;");
        assertFalse(p.queryPred("f", List.of(new ArrayList(Arrays.asList(1, 2, 3)))).results().isEmpty());
        assertTrue(p.queryPred("f", List.of(new ArrayList(Arrays.asList(1, 2, 3, 4)))).results().isEmpty());

        assertFalse(p.queryPred("f", List.of(new int[] { 1, 2, 3 })).results().isEmpty());
        assertTrue(p.queryPred("f", List.of(new int[] { 1, 2, 3, 4 })).results().isEmpty());
    }

    @Test
    public void testExternalIsa() throws Exception {
        p.loadStr("f(a: MyClass, x) if x = a.id;");
        List<HashMap<String, Object>> result = p.queryPred("f", List.of(new MyClass("test", 1), new Variable("x")))
                .results();
        assertTrue(result.equals(List.of(Map.of("x", 1))));
        p.clear();

        p.loadStr("f(a: MySubClass, x) if x = a.id;");
        result = p.queryPred("f", List.of(new MyClass("test", 1), new Variable("x"))).results();
        assertTrue(result.isEmpty(), "Failed to filter rules by specializers.");
        p.clear();

        p.loadStr("f(a: OtherClass, x) if x = a.id;");
        assertThrows(Exceptions.UnregisteredClassError.class,
                () -> p.queryPred("f", List.of(new MyClass("test", 1), new Variable("x"))).results());
    }

    @Test
    public void testExternalIsSubSpecializer() throws Exception {
        p.loadStr("f(a: MySubClass, x) if x = 1;");
        p.loadStr("f(a: MyClass, x) if x = 2;");
        List<HashMap<String, Object>> result = p.queryPred("f", List.of(new MySubClass("test", 1), new Variable("x")))
                .results();
        assertTrue(result.equals(List.of(Map.of("x", 1), Map.of("x", 2))),
                "Failed to order rules based on specializers.");

        result = p.queryPred("f", List.of(new MyClass("test", 1), new Variable("x"))).results();
        assertTrue(result.equals(List.of(Map.of("x", 2))), "Failed to order rules based on specializers.");
    }

    @Test
    public void testReturnListFromCall() throws Exception {
        p.loadStr("test(c: MyClass) if \"hello\" in c.myList;");
        MyClass c = new MyClass("test", 1);
        assertFalse(p.queryPred("test", List.of(c)).results().isEmpty());
    }

    @Test
    public void testClassMethods() throws Exception {
        p.loadStr("test(x) if x=1 and MyClass.myStaticMethod = \"hello world\";");

        assertFalse(p.queryStr("test(1)").results().isEmpty());
    }

    /**** TEST PARSING ****/

    @Test
    public void testIntegerOverFlowError() throws Exception {
        String rule = "f(x) if x = 18446744073709551616;";
        Exceptions.IntegerOverflow e = assertThrows(Exceptions.IntegerOverflow.class, () -> p.loadStr(rule));
        assertEquals("'18446744073709551616' caused an integer overflow at line 1, column 13", e.getMessage());

    }

    @Test
    public void testInvalidTokenCharacter() throws Exception {
        String rule = "f(x) if x = \"This is not\n allowed\"";
        Exceptions.InvalidTokenCharacter e = assertThrows(Exceptions.InvalidTokenCharacter.class,
                () -> p.loadStr(rule));
        // TODO: this is a wacky message
        assertEquals("'\\n' is not a valid character. Found in This is not at line 1, column 25", e.getMessage());

    }

    @Test
    public void testUnrecognizedTokenError() throws Exception {
        String rule = "1";
        Exceptions.UnrecognizedToken e = assertThrows(Exceptions.UnrecognizedToken.class, () -> p.loadStr(rule));
        assertEquals("did not expect to find the token '1' at line 1, column 1", e.getMessage());

    }

    /**** TEST LOADING ****/

    @Test
    public void testLoadFile() throws Exception {
        p.loadFile("src/test/java/com/osohq/oso/test.polar");
        assertTrue(p.queryStr("f(x)").results().equals(List.of(Map.of("x", 1), Map.of("x", 2), Map.of("x", 3))));
    }

    @Test
    public void testLoadNonPolarFile() throws Exception {
        assertThrows(Exceptions.PolarFileExtensionError.class, () -> p.loadFile("wrong.txt"),
                "Failed to catch incorrect Polar file extension.");
    }

    @Test
    public void testLoadFilePassesFilename() throws Exception {
        File tempFile = File.createTempFile("error-", ".polar");
        FileWriter w = new FileWriter(tempFile);
        w.write(";");
        w.close();
        p.loadFile(tempFile.getPath());
        assertThrows(Exceptions.ParseError.class, () -> p.queryStr("f(1)"),
                "Failed to pass filename across FFI boundary.");
        tempFile.deleteOnExit();
    }

    @Test
    public void testLoadFileIdempotent() throws Exception {
        p.loadFile("src/test/java/com/osohq/oso/test.polar");
        p.loadFile("src/test/java/com/osohq/oso/test.polar");
        assertTrue(p.queryStr("f(x)").results().equals(List.of(Map.of("x", 1), Map.of("x", 2), Map.of("x", 3))),
                "loadFile behavior is not idempotent.");
    }

    @Test
    public void testLoadMultipleFiles() throws Exception {
        p.loadFile("src/test/java/com/osohq/oso/test.polar");
        p.loadFile("src/test/java/com/osohq/oso/test2.polar");
        assertTrue(p.queryStr("f(x)").results().equals(List.of(Map.of("x", 1), Map.of("x", 2), Map.of("x", 3))));
        assertTrue(p.queryStr("g(x)").results().equals(List.of(Map.of("x", 1), Map.of("x", 2), Map.of("x", 3))));
    }

    @Test
    public void testClear() throws Exception {
        p.loadFile("src/test/java/com/osohq/oso/test.polar");
        assertEquals(List.of(Map.of("x", 1), Map.of("x", 2), Map.of("x", 3)), p.queryStr("f(x)").results());
        p.clear();
        assertTrue(p.queryStr("f(x)").results().isEmpty());
    }

    /*** TEST OSO ***/
    @Test
    public void testPathMapper() throws Exception {
        Oso oso = new Oso();
        // Extracts matches into a hash
        PathMapper mapper = new PathMapper("/widget/{id}");
        assertTrue(mapper.map("/widget/12").equals(Map.of("id", "12")), "Failed to extract matches to a hash");
        // maps HTTP resources
        oso.registerClass(MyClass.class, m -> new MyClass("test", Integer.parseInt((String) m.get("id"))), "MyClass");
        oso.loadStr("allow(actor, \"get\", _: Http{path: path}) if "
                + "new PathMapper{template: \"/myclass/{id}\"}.map(path) = {id: id} and "
                + "allow(actor, \"get\", new MyClass{id: id});\n"
                + "allow(actor, \"get\", myclass: MyClass) if myclass.id = 12;");
        Http http12 = new Http(null, "/myclass/12", null);
        assertTrue(oso.allow("sam", "get", http12), "Failed to correctly map HTTP resource");
        Http http13 = new Http(null, "/myclass/13", null);
        assertFalse(oso.allow("sam", "get", http13), "Failed to correctly map HTTP resource");
    }
}
