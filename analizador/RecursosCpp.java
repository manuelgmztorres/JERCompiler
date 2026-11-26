import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Lo que GeneradorCppCuadruplos escribe tal cual en el C++ que produce: palabras que C++ no deja usar
 * como nombre, las funciones auxiliares de ejecucion (division entre cero, indices, OBT...) y el arranque
 * UTF-8 de la consola de Windows. Es solo texto: la traduccion esta en GeneradorCppCuadruplos.
 */
final class RecursosCpp {
  private RecursosCpp() {}

  // Palabras que un identificador de JER (empieza con letra) puede escribir pero C++ no acepta
  // como nombre: palabras clave, macros de los encabezados incluidos, el namespace `jer` y la funcion
  // auxiliar `decimal`. Los nombres de funciones de la biblioteca de C (abs, log...) no hacen falta:
  // el namespace `jer` los separa. Un nombre que empiece con `jer_` tambien se renombra (ver nombre()).
  static final Set<String> RESERVADAS_CPP = new HashSet<String>(Arrays.asList((
      "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char16_t char32_t class "
    + "compl const constexpr const_cast continue decltype default delete do double dynamic_cast else enum "
    + "explicit export extern false float for friend goto if inline int long mutable namespace new noexcept "
    + "not not_eq nullptr operator or or_eq private protected public register reinterpret_cast return short "
    + "signed sizeof static static_assert static_cast struct switch template this thread_local throw true try "
    + "typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq "
    + "main std jer decimal NULL EOF errno assert stdin stdout stderr INFINITY NAN HUGE_VAL EXIT_SUCCESS EXIT_FAILURE").split(" ")));

  static final String SANGRIA = "    ";

  static final Map<String, String[]> REQUIERE = new HashMap<String, String[]>();
  static {
    REQUIERE.put("jer_dividir", new String[] { "jer_error" });
    REQUIERE.put("jer_modulo", new String[] { "jer_error" });
    REQUIERE.put("jer_potencia", new String[] { "jer_error" });
    REQUIERE.put("jer_raiz", new String[] { "jer_error" });
    REQUIERE.put("jer_elem", new String[] { "jer_error" });
    REQUIERE.put("jer_tamanio", new String[] { "jer_error" });
    REQUIERE.put("jer_aEntero", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_aDecimal", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_leerCaracter", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_leerBooleano", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_palabra", new String[] { "jer_error" });
  }


  /** {nombre, #include que necesita, codigo}. */
  static final String[][] AUXILIARES = {
    { "decimal", "iomanip sstream string",
        "// Un DEC siempre con punto decimal, como en JER (3.0 y no 3), y con hasta 15 cifras.\n"
      + "std::string decimal(double d) {\n"
      + "    std::ostringstream texto;\n"
      + "    texto << std::setprecision(15) << d;\n"
      + "    std::string s = texto.str();\n"
      + "    if (s.find_first_of(\".ein\") == std::string::npos) s += \".0\";\n"
      + "    return s;\n"
      + "}\n" },
    { "jer_error", "cstdlib iostream string",
        "// Error de ejecucion: avisa en que linea del programa JER ocurrio y termina el programa.\n"
      + "[[noreturn]] void jer_error(const std::string& causa, int linea) {\n"
      + "    std::cout.flush();\n"
      + "    std::cerr << \"ERROR DE EJECUCION: \" << causa << \" (linea \" << linea << \")\" << std::endl;\n"
      + "    std::exit(1);\n"
      + "}\n" },
    { "jer_dividir", "",
        "int jer_dividir(int a, int b, int linea) {\n"
      + "    if (b == 0) jer_error(\"division entre cero\", linea);\n"
      + "    return a / b;\n"
      + "}\n\n"
      + "double jer_dividir(double a, double b, int linea) {\n"
      + "    if (b == 0.0) jer_error(\"division entre cero\", linea);\n"
      + "    return a / b;\n"
      + "}\n" },
    { "jer_modulo", "cmath",
        "int jer_modulo(int a, int b, int linea) {\n"
      + "    if (b == 0) jer_error(\"modulo entre cero\", linea);\n"
      + "    return a % b;\n"
      + "}\n\n"
      + "double jer_modulo(double a, double b, int linea) {\n"
      + "    if (b == 0.0) jer_error(\"modulo entre cero\", linea);\n"
      + "    return std::fmod(a, b);\n"
      + "}\n" },
    { "jer_potencia", "",
        "// Potencia entre ENT, exacta (std::pow trabaja con double y puede dar 99 en vez de 100).\n"
      + "int jer_potencia(int base, int exponente, int linea) {\n"
      + "    if (exponente < 0) jer_error(\"potencia entre ENT con exponente negativo\", linea);\n"
      + "    int resultado = 1;\n"
      + "    for (int i = 0; i < exponente; i++) resultado *= base;\n"
      + "    return resultado;\n"
      + "}\n" },
    { "jer_raiz", "cmath",
        "// a // n: raiz de indice n de a. Con base negativa solo hay raiz si el indice es entero e impar.\n"
      + "double jer_raiz(double a, double n, int linea) {\n"
      + "    if (n == 0.0) jer_error(\"raiz de indice 0\", linea);\n"
      + "    if (a < 0.0) {\n"
      + "        if (n != std::floor(n) || std::fmod(n, 2.0) == 0.0) jer_error(\"raiz de indice par de un numero negativo\", linea);\n"
      + "        return -std::pow(-a, 1.0 / n);\n"
      + "    }\n"
      + "    return std::pow(a, 1.0 / n);\n"
      + "}\n\n"
      + "// Entre ENT se redondea para que 64 // 3 de 4 y no 3.\n"
      + "int jer_raiz(int a, int n, int linea) {\n"
      + "    return (int) std::round(jer_raiz((double) a, (double) n, linea));\n"
      + "}\n" },
    { "jer_elem", "string vector",
        "// Elemento i de un arreglo (o caracter i de una CAD), revisando que el indice exista.\n"
      + "template <typename T>\n"
      + "T& jer_elem(std::vector<T>& v, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) v.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de rango (tamano \" + std::to_string(v.size()) + \")\", linea);\n"
      + "    return v[i];\n"
      + "}\n\n"
      + "std::vector<bool>::reference jer_elem(std::vector<bool>& v, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) v.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de rango (tamano \" + std::to_string(v.size()) + \")\", linea);\n"
      + "    return v[i];\n"
      + "}\n\n"
      + "char& jer_elem(std::string& s, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) s.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de la cadena (longitud \" + std::to_string(s.size()) + \")\", linea);\n"
      + "    return s[i];\n"
      + "}\n\n"
      + "char jer_elem(const std::string& s, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) s.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de la cadena (longitud \" + std::to_string(s.size()) + \")\", linea);\n"
      + "    return s[i];\n"
      + "}\n" },
    { "jer_tamanio", "string",
        "int jer_tamanio(int n, int linea) {\n"
      + "    if (n <= 0) jer_error(\"tamano de arreglo \" + std::to_string(n) + \" (debe ser mayor que 0)\", linea);\n"
      + "    return n;\n"
      + "}\n" },
    { "jer_palabra", "iostream string",
        "// Siguiente palabra de la entrada (OBT de ENT, DEC, CAR o BOO).\n"
      + "std::string jer_palabra(int linea) {\n"
      + "    std::string palabra;\n"
      + "    if (!(std::cin >> palabra)) jer_error(\"no hay mas datos para leer\", linea);\n"
      + "    return palabra;\n"
      + "}\n" },
    { "jer_aEntero", "string",
        "// Texto a ENT (OBT y CONV(ENT, texto)): todo el texto debe ser un numero entero.\n"
      + "int jer_aEntero(const std::string& texto, int linea) {\n"
      + "    std::size_t usados = 0;\n"
      + "    int valor = 0;\n"
      + "    try { valor = std::stoi(texto, &usados); } catch (...) { usados = 0; }\n"
      + "    if (usados == 0 || usados != texto.size()) jer_error(\"'\" + texto + \"' no es un ENT\", linea);\n"
      + "    return valor;\n"
      + "}\n" },
    { "jer_aDecimal", "string",
        "// Texto a DEC (OBT y CONV(DEC, texto)): todo el texto debe ser un numero.\n"
      + "double jer_aDecimal(const std::string& texto, int linea) {\n"
      + "    std::size_t usados = 0;\n"
      + "    double valor = 0.0;\n"
      + "    try { valor = std::stod(texto, &usados); } catch (...) { usados = 0; }\n"
      + "    if (usados == 0 || usados != texto.size()) jer_error(\"'\" + texto + \"' no es un DEC\", linea);\n"
      + "    return valor;\n"
      + "}\n" },
    { "jer_leerCaracter", "string",
        "char jer_leerCaracter(int linea) {\n"
      + "    std::string palabra = jer_palabra(linea);\n"
      + "    if (palabra.size() != 1) jer_error(\"'\" + palabra + \"' no es un CAR (debe ser un solo caracter)\", linea);\n"
      + "    return palabra[0];\n"
      + "}\n" },
    { "jer_leerBooleano", "string",
        "bool jer_leerBooleano(int linea) {\n"
      + "    std::string palabra = jer_palabra(linea);\n"
      + "    if (palabra == \"VERDADERO\") return true;\n"
      + "    if (palabra == \"FALSO\") return false;\n"
      + "    jer_error(\"'\" + palabra + \"' no es un BOO (escriba VERDADERO o FALSO)\", linea);\n"
      + "}\n" },
  };

  /**
   * La consola de Windows no usa UTF-8 por defecto y mostraria mal los acentos y la ñ. Se declaran
   * solo las dos funciones de Windows que hacen falta, sin incluir <windows.h> (trae macros como
   * min/max que chocarian con nombres del programa).
   */
  static final String DECLARACION_UTF8 =
      "#ifdef _WIN32\n"
    + "// Para que la consola de Windows muestre bien los acentos y la ñ.\n"
    + "extern \"C\" __declspec(dllimport) int __stdcall SetConsoleOutputCP(unsigned int);\n"
    + "extern \"C\" __declspec(dllimport) int __stdcall SetConsoleCP(unsigned int);\n"
    + "#endif\n";

  static final String ACTIVAR_UTF8 =
      "#ifdef _WIN32\n"
    + SANGRIA + "SetConsoleOutputCP(65001);\n"
    + SANGRIA + "SetConsoleCP(65001);\n"
    + "#endif\n";
}
