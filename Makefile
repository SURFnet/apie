# Recipes for building release artifacts
#
# Expects the artifact to be in the format
#
#        `eduhub-validator-VERSION-ARCH.EXT
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
# In other words, `make eduhub-validator-$VERSION-windows-amd64.zip`
# and `make eduhub-validator-$VERSION-macos-aarch64.tar.gz` will do
# what you expect as long as $VERSION is the currently tagged version

VALIDATOR_VERSION:=$(shell git describe --tags)
BABASHKA_VERSION:=1.3.186-SNAPSHOT  # need latest snapshot for standalone executables

.PHONY: uberjar

exec_base_name=eduhub-validator
release_name=$(exec_base_name)-$(VALIDATOR_VERSION)
source_files=$(shell find src assets profiles -type f)

# uberjar is the babashka uberjar (not a java-compatible jar)
uberjar=$(exec_base_name)-$(VALIDATOR_VERSION)-standalone.jar

uberjar: $(uberjar)

$(uberjar): deps.edn bb.edn $(source_files)
	bb uberjar $@ -m nl.jomco.eduhub-validator.main

release: $(binary_release)

# for unixy systems
$(release_name)-%/$(exec_base_name): babashka-$(BABASHKA_VERSION)-%.tar.gz $(uberjar)
	mkdir -p $(dir $@)
	tar -xO <$< >$@
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
