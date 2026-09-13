#!/bin/sh

set -eux

cd "$(dirname "$0")"

docker run -ti -v "$(pwd)":/src -w /src --user "$(id -u)" ghcr.io/ylarod/ddk-min:android16-6.12 make $*
cp dirtyfrag.ko dirtyfrag-unstripped.ko
# Size diet: this ko is written through the exploit page by page.
# --strip-unneeded keeps only load-relevant symbols (undefined imports etc.);
# the -R removals drop loader-ignored sections. Removing the empty .hyp.*
# sections also repacks the file, reclaiming ~2.2 KiB of alignment padding.
# (13.4 KiB -> ~7.8 KiB, i.e. 4 pages -> 2 pages.)
llvm-objcopy --strip-unneeded \
  -R .comment -R .note.gnu.build-id -R .note.gnu.property -R .note.Linux -R .note.GNU-stack \
  -R .BTF -R .BTF.base -R .llvm_addrsig \
  -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
  -R .hyp.patchable_function_entries -R .hyp.data \
  dirtyfrag.ko

cp dirtyfrag.ko ../app/src/main/jni/dirtyfrag.ko
