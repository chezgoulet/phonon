cmake_minimum_required(VERSION 3.22.1)
project(phonon-fadvise C)

add_library(phonon-fadvise SHARED fadvise.c)
