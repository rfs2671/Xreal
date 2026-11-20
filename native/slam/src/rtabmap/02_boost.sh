#!/usr/bin/env bash
# Boost 1.86.0 through its own CMake build: only what PCL 1.15 / rtabmap need.
. "$(dirname "$0")/common.sh"
LIBS="filesystem;iostreams;system;serialization;mpl;preprocessor;random;graph;algorithm;fusion;asio;property_tree;signals2;smart_ptr;math;lexical_cast;dynamic_bitset;sort;predef;optional;core;utility;unordered;tokenizer;range;ptr_container;pool;numeric/conversion;interprocess;format;foreach;crc;concept_check;circular_buffer;bimap;array;date_time;thread;uuid;multi_array"
cm "$SRC/boost" "$BLD/boost" -DBOOST_INCLUDE_LIBRARIES="$LIBS" -DBUILD_TESTING=OFF \
  -DBOOST_IOSTREAMS_ENABLE_BZIP2=OFF -DBOOST_IOSTREAMS_ENABLE_LZMA=OFF -DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF \
  -DBOOST_INSTALL_LAYOUT=system
bi "$BLD/boost"
