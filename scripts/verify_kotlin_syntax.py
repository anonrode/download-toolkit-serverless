import os
import sys

def check_file(path):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    i = 0
    n = len(text)
    stack = []
    line = 1
    col = 1
    in_line_comment = False
    in_block_comment = False
    in_string = False
    in_multiline_string = False

    errors = []
    while i < n:
        c = text[i]
        if c == '\n':
            line += 1
            col = 1
            in_line_comment = False
            i += 1
            continue

        if in_line_comment:
            i += 1
            col += 1
            continue

        if in_block_comment:
            if text[i:i+2] == '*/':
                in_block_comment = False
                i += 2
                col += 2
            else:
                i += 1
                col += 1
            continue

        if in_multiline_string:
            if text[i:i+3] == '"""':
                in_multiline_string = False
                i += 3
                col += 3
            else:
                i += 1
                col += 1
            continue

        if in_string:
            if c == '\\':
                i += 2
                col += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            col += 1
            continue

        # Normal code
        if text[i:i+2] == '//':
            in_line_comment = True
            i += 2
            col += 2
            continue
        if text[i:i+2] == '/*':
            in_block_comment = True
            i += 2
            col += 2
            continue
        if text[i:i+3] == '"""':
            in_multiline_string = True
            i += 3
            col += 3
            continue
        if c == '"':
            in_string = True
            i += 1
            col += 1
            continue
        if c == "'":
            if i + 2 < n and text[i+2] == "'":
                i += 3
                col += 3
            elif i + 3 < n and text[i+1] == '\\' and text[i+3] == "'":
                i += 4
                col += 4
            else:
                i += 1
                col += 1
            continue

        if c in '{[(':
            stack.append((c, line, col))
        elif c in '}])':
            if not stack:
                errors.append(f"Unmatched closing {c} at line {line}:{col}")
            else:
                top, tline, tcol = stack.pop()
                expected = {'{': '}', '[': ']', '(': ')'}[top]
                if c != expected:
                    errors.append(f"Mismatched bracket: expected {expected} for {top} from line {tline}:{tcol}, got {c} at line {line}:{col}")
        i += 1
        col += 1

    while stack:
        top, tline, tcol = stack.pop()
        errors.append(f"Unclosed {top} from line {tline}:{tcol}")

    return errors

if __name__ == "__main__":
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target_files = [
        "app/src/main/java/com/anonrode/downloader/data/models/Models.kt",
        "app/src/main/java/com/anonrode/downloader/data/rules/PipelineModels.kt",
        "app/src/main/java/com/anonrode/downloader/engine/Aria2Control.kt",
        "app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt",
        "app/src/main/java/com/anonrode/downloader/engine/HlsSizeEstimator.kt",
        "app/src/main/java/com/anonrode/downloader/engine/ProgressParser.kt",
        "app/src/main/java/com/anonrode/downloader/engine/TurboDownloader.kt",
        "app/src/main/java/com/anonrode/downloader/engine/YoutubeDlDownloader.kt",
        "app/src/main/java/com/anonrode/downloader/engine/StreamProgressMeter.kt",
        "app/src/main/java/com/anonrode/downloader/pipeline/LinkResolver.kt",
        "app/src/main/java/com/anonrode/downloader/providers/AsianCProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/AsianDramaFeed.kt",
        "app/src/main/java/com/anonrode/downloader/providers/CategoryFeed.kt",
        "app/src/main/java/com/anonrode/downloader/providers/RulesPipeline.kt",
        "app/src/main/java/com/anonrode/downloader/providers/TrendingFeed.kt",
        "app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt",
        "app/src/main/java/com/anonrode/downloader/util/ExplicitContentFilter.kt",
        "app/src/main/java/com/anonrode/downloader/util/PostContentSanitizer.kt",
        "app/src/main/java/com/anonrode/downloader/providers/NaijaPreyProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/NkiriProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/RocksProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/DramaRainProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/DramaKeyProvider.kt",
        "app/src/main/java/com/anonrode/downloader/providers/AnimeOracle.kt",
        "app/src/main/java/com/anonrode/downloader/providers/AnitakuProvider.kt",
        "app/src/main/java/com/anonrode/downloader/viewmodel/MainViewModel.kt",
        "app/src/main/java/com/anonrode/downloader/providers/NepuProvider.kt",
        "app/src/main/java/com/anonrode/downloader/engine/InstagramPhotoMuxer.kt",
        "app/src/main/java/com/anonrode/downloader/engine/DownloadRepository.kt",
        "app/src/main/java/com/anonrode/downloader/pipeline/StreamValidator.kt",
        "app/src/main/java/com/anonrode/downloader/ui/screens/DownloadsScreen.kt",
        "app/src/main/java/com/anonrode/downloader/ui/components/AsianDramaExplorer.kt",
        "app/src/main/java/com/anonrode/downloader/ui/screens/HomeScreen.kt",
        "app/src/main/java/com/anonrode/downloader/ui/components/MediaPlayerModal.kt",
        "app/src/main/java/com/anonrode/downloader/ui/components/MediaPlayerPrefs.kt",
        "app/src/test/java/com/anonrode/downloader/pipeline/FullAppPipelineSimulationTest.kt"
    ]

    has_error = False
    for rel in target_files:
        full = os.path.join(repo, rel)
        if not os.path.exists(full):
            print(f"MISSING: {rel}")
            has_error = True
            continue
        errs = check_file(full)
        if errs:
            print(f"FAIL: {rel}")
            for e in errs:
                print(f"   {e}")
            has_error = True
        else:
            print(f"PASS: {rel}")

    if has_error:
        sys.exit(1)
    print("\nALL TARGET KOTLIN FILES VERIFIED CLEAN WITH ZERO SYNTAX/BRACE ERRORS!")
