# Murmur for macOS. Needs: Command Line Tools (swift), `brew install whisper-cpp llama.cpp`.
APP      := build/Murmur.app
CONFIG   ?= release
BIN      := .build/$(CONFIG)
RES      := Sources/MurmurCore/Resources/prompts
SIGN_ID  ?= -

.PHONY: all prompts build cli app run install regrant selftest guard-diff clean

all: app

# prompts/ (repo root) is the single source of truth for the system prompts and tones: copied, not forked.
prompts:
	@mkdir -p $(RES)/system $(RES)/tones
	@cp ../prompts/system/default.v1.md ../prompts/system/finetuned.v1.md ../prompts/system/preferences_block.v1.md $(RES)/system/
	@cp ../prompts/tones/tones.v1.json $(RES)/tones/

build: prompts
	swift build -c $(CONFIG)

cli: prompts
	swift build -c $(CONFIG) --product murmur-cli

app: build
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS $(APP)/Contents/Resources
	cp $(BIN)/Murmur $(APP)/Contents/MacOS/Murmur
	cp -R $(BIN)/Murmur_MurmurCore.bundle $(APP)/Contents/Resources/
	cp Support/Info.plist $(APP)/Contents/Info.plist
	cp Support/AppIcon.icns $(APP)/Contents/Resources/ 2>/dev/null || true
	codesign --force --deep --sign "$(SIGN_ID)" --identifier app.murmur.mac $(APP)
	@echo "built $(APP)"

run: app
	open $(APP)

install: app
	rm -rf /Applications/Murmur.app && cp -R $(APP) /Applications/
	@echo "installed /Applications/Murmur.app"

# Ad-hoc signatures change on every build and macOS then ignores the old Accessibility grant (the switch may
# still look on). Drop Murmur's stale entries and open the pane to add it again.
regrant:
	-tccutil reset Accessibility app.murmur.mac
	-tccutil reset ScreenCapture app.murmur.mac
	open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"

selftest: cli
	$(BIN)/murmur-cli selftest ../prompts

# Swift guard vs prompts/guard.py on generated cases.
guard-diff: cli
	python3 tools/guard_diff.py $(BIN)/murmur-cli

clean:
	rm -rf .build build $(RES)
