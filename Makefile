# Recipes for building release artifacts
#
# Expects the artifact to be in the format
#
#        `apie-VERSION-ARCH.EXT
#
# Where VERSION matches the current git tag (like `v0.0.1-SNAPSHOT`)
#
# And ARCH.EXT is some reasonable combination, like
# `linux-amd64.tar.gz`, `standalone.jar` or `windows-amd64.zip`
#
# Standalone binaries are supported for every platform supported by
# babashka (variants of linux, windows and macos), see
# https://github.com/babashka/babashka/releases
#
# In other words, `make apie-$VERSION-windows-amd64.zip`
# and `make apie-$VERSION-macos-aarch64.tar.gz` will do
# what you expect as long as $VERSION is the currently tagged version

BB:=bb
version:=$(shell git describe --tags)

# need latest snapshot for standalone executables
BABASHKA_VERSION:=1.3.188

.PHONY: uberjar

exec_base_name=apie
release_name=$(exec_base_name)-$(version)
source_files=$(shell find src assets -type f)
current_arch=$(shell bb dev/current_arch.clj)

# uberjar is the babashka uberjar (not a java-compatible jar)
uberjar=$(exec_base_name)-$(version)-standalone.jar

uberjar: $(uberjar)

$(uberjar): deps.edn bb.edn $(source_files)
	$(BB) uberjar $@ -m nl.jomco.apie.main

release: $(binary_release)

# for unixy systems

$(release_name)-%/$(exec_base_name): babashka-$(BABASHKA_VERSION)-%.tar.gz $(uberjar)
	mkdir -p $(dir $@)
	tar -zxO <$< >$@
	cat $(uberjar) >>$@
	chmod 755 $@

# for windows
$(release_name)-%/$(exec_base_name).exe: babashka-$(BABASHKA_VERSION)-%.zip $(uberjar)
	mkdir -p $(dir $@)
	unzip -p $< >$@
	cat $(uberjar) >>$@

babashka-$(BABASHKA_VERSION)-%:
	curl -sL https://github.com/babashka/babashka-dev-builds/releases/download/v$(BABASHKA_VERSION)/$@ -o $@

# for unixy systems
$(release_name)-%.tar.gz: $(release_name)-%/$(exec_base_name)
	tar -rf tmp.tar $<
	gzip <tmp.tar >$@
	rm tmp.tar

# for windows
$(release_name)-%.zip: $(release_name)-%/$(exec_base_name).exe
	zip -r $@ $(basename $@)

# build for local use, on windows
$(exec_base_name).exe: $(release_name)-$(current_arch)/$(exec_base_name).exe
	cp $< $@

# build for local use, non-windows
$(exec_base_name): $(uberjar)
	cat $(shell which bb) $(uberjar) >$@
	chmod 755 $@
