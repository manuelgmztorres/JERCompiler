#include <cstdlib>
#include <iostream>
#include <string>

// Error de ejecucion: avisa en que linea del programa JER ocurrio y termina el programa.
[[noreturn]] void jer_error(const std::string& causa, int linea) {
    std::cout.flush();
    std::cerr << "ERROR DE EJECUCION: " << causa << " (linea " << linea << ")" << std::endl;
    std::exit(1);
}

// Siguiente palabra de la entrada (OBT de ENT, DEC, CAR o BOO).
std::string jer_palabra(int linea) {
    std::string palabra;
    if (!(std::cin >> palabra)) jer_error("no hay mas datos para leer", linea);
    return palabra;
}

// Texto a ENT (OBT y CONV(ENT, texto)): todo el texto debe ser un numero entero.
int jer_aEntero(const std::string& texto, int linea) {
    std::size_t usados = 0;
    int valor = 0;
    try { valor = std::stoi(texto, &usados); } catch (...) { usados = 0; }
    if (usados == 0 || usados != texto.size()) jer_error("'" + texto + "' no es un ENT", linea);
    return valor;
}

namespace jer {

void _inicio();
void principal();

void _inicio() {
    principal();
    return;
}

void principal() {
    int x = 0;

    x = 0;
    x = jer_aEntero(jer_palabra(3), 3);
    std::cout << x << std::endl;
    return;
}

} // namespace jer

int main() {
    jer::_inicio();
    return 0;
}
