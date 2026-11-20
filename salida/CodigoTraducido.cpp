#include <iostream>

void principal() {
    int edad = 25;
    if (edad >= 18) {
        std::cout << "Eres Mayor de Edad" << std::endl;
    } else {
        std::cout << "No Eres Mayor de Edad" << std::endl;
    }
}

int main() {
    principal();
    return 0;
}
