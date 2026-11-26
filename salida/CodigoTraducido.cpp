#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>

// Un DEC siempre con punto decimal, como en JER (3.0 y no 3), y con hasta 15 cifras.
std::string decimal(double d) {
    std::ostringstream texto;
    texto << std::setprecision(15) << d;
    std::string s = texto.str();
    if (s.find_first_of(".ein") == std::string::npos) s += ".0";
    return s;
}

namespace jer {

void _inicio();
void principal();

void _inicio() {
    principal();
    return;
}

void principal() {
    int n1 = 0;
    int n2 = 0;
    int t1 = 0;
    double div = 0.0;

    std::cout << "Hola Mundo" << std::endl;
    n1 = 15;
    n2 = 4;
    t1 = 3;
    div = (double) (3);
    std::cout << decimal(div) << std::endl;
    return;
}

} // namespace jer

int main() {
    jer::_inicio();
    return 0;
}
