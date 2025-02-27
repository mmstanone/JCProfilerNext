#!/usr/bin/env python3

from argparse import ArgumentParser, Namespace
from pathlib import Path
from shutil import copy, copytree, make_archive, rmtree
from subprocess import PIPE, STDOUT, run
from tempfile import mkdtemp
from typing import Any, Callable, Dict, List, Optional
from urllib.request import Request, urlopen

import json
import os
import platform
import re
import stat
import sys


STAGES = ['instrumentation', 'compilation', 'installation', 'profiling',
          'visualisation', 'all']

ARGS: Namespace
FAILURES: List[str] = []
SKIPS: List[str] = []

BOLD_RED = '\033[1;31m'
BOLD_GREEN = '\033[1;32m'
BOLD_YELLOW = '\033[1;33m'
RESET = '\033[00m'


def print(*args: Any, colour: str = BOLD_GREEN, **kwargs: Any) -> None:
    import builtins
    builtins.print(colour, end='')
    builtins.print(*args, **kwargs)
    builtins.print(RESET, end='', flush=True)


def rebuild_jar() -> None:
    suffix = '.bat' if os.name == 'nt' else ''
    script_path = Path('../gradlew' + suffix).absolute()

    print('Rebuilding project')
    run([script_path, '--project-dir=..', 'build'], check=True)


def clone_git_repo(repo: str, target: str, reclone: bool = True) -> None:
    if os.path.exists(target):
        if not reclone:
            return

        def remove_readonly(func: Callable[[Path], None],
                            path: Path, _: Exception) -> None:
            os.chmod(path, stat.S_IWRITE)
            func(path)
        rmtree(target, onerror=remove_readonly)

    run(['git', 'clone', '--depth=1', repo, target], check=True)
    print(target, 'cloned successfully')
    return


def download_file(url: str, target: str) -> None:
    if os.path.exists(target):
        return

    print('Downloading', url)
    req = Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urlopen(req) as res, open(target, 'wb') as f:
        f.write(res.read())


def prepare_etsi(version: str) -> str:
    if version != '143.019':
        raise NotImplementedError

    src_dir = Path(f'etsiapi/{version}/05.06.00/java').absolute()
    result = src_dir / 'etsiapi.jar'
    result_str = str(result)

    if os.path.exists(result):
        return result_str

    # add export files
    copytree(src_dir.parent / 'exports', src_dir, dirs_exist_ok=True)

    make_archive(result_str, 'zip', src_dir)

    # fix extension
    os.rename(result.with_suffix(result.suffix + '.zip'), result)
    return result_str


def prepare_thoth(version: str) -> str:
    if version != 'KM101':
        raise NotImplementedError

    src_dir = Path('thoth').absolute()
    result = src_dir / 'thoth.jar'
    result_str = str(result)

    if os.path.exists(result):
        return result_str

    pkg_dir = src_dir / 'result' / version / 'javacard'

    # create dirs
    os.makedirs(pkg_dir)

    # compile the sources
    run(['javac', str(src_dir / 'T101OpenAPI.java'), '-classpath',
         Path('jckit').absolute() / 'jc222_kit' / 'lib' / 'api.jar'],
        check=True)

    # add sources and the export file
    copy(src_dir / 'T101OpenAPI.class', pkg_dir.parent)
    copy(src_dir / 'T101OpenAPI.java', pkg_dir.parent)
    copy(src_dir / f'{version}.exp', pkg_dir)

    make_archive(result_str, 'zip', src_dir / 'result')

    # fix extension
    os.rename(result.with_suffix(result.suffix + '.zip'), result)
    return result_str


def modify_repo(test: Dict[str, Any]) -> None:
    for rm in test.get('remove', []):
        for file in Path(test['name']).glob(rm):
            print('Removing', file)
            os.unlink(file)

    for replace in test.get('fixup', []):
        pattern = re.compile(replace['pattern'], re.MULTILINE)
        pattern_str: str = replace['pattern'].replace('\n', '\\n')
        replacement: str = replace['replacement']

        for glb in replace['files']:
            for file in Path(test['name']).glob(glb):
                print('Replacing lines matching', f'"{pattern_str}"', 'with',
                      f'"{replacement}"', 'in', file)
                with open(file, 'r', encoding='utf8', errors='ignore') as f:
                    lines = f.read()
                with open(file, 'w', encoding='utf8') as f:
                    f.write(pattern.sub(replacement, lines))


def execute_cmd(cmd: List[str], stages: List[str] = []) -> bool:
    if ARGS.card or ARGS.mode == 'stats':
        stages = ['all']

    for stage in stages:
        stage_cmd = cmd.copy()

        if stage != 'all':
            stage_cmd += ['--start-from', str(stage)]
            stage_cmd += ['--stop-after', str(stage)]
            print('Executing stage', stage)

        print('Command: ', end='')
        print(*stage_cmd, colour=BOLD_YELLOW)

        ret = run(stage_cmd).returncode
        if ret != 0:
            print('Command failed with return code', ret, colour=BOLD_RED)
            if not ARGS.card:
                sys.exit(1)
            return False

    return True


def prepare_workdir(test: Dict[str, Any], subtest_name: str) -> Path:
    if not os.path.exists(ARGS.output_dir):
        os.mkdir(ARGS.output_dir)

    test_dir = Path(mkdtemp(prefix=f'{test["name"]}_{subtest_name}_',
                    dir=ARGS.output_dir)).absolute()
    print('Created temporary directory', test_dir)

    copytree(Path(test['name']) / test['path'], test_dir,
             dirs_exist_ok=True)
    return test_dir


def get_stats(test: Dict[str, Any], cmd: List[str]) -> None:
    stats_cmd = cmd.copy()
    test_dir = prepare_workdir(test, 'stats')

    stats_cmd += ['--work-dir', str(test_dir)]
    stats_cmd += ['--mode', 'stats']

    if not execute_cmd(stats_cmd):
        FAILURES.append(f'{test["name"]} in stats mode')


def test_ctor(test: Dict[str, Any], cmd: List[str], dir_prefix: str,
              stages: List[str]) -> None:
    ctor_cmd = cmd.copy()
    test_dir = prepare_workdir(test, dir_prefix + 'ctor')

    ctor_cmd += ['--work-dir', str(test_dir)]
    ctor_cmd += ['--mode', 'memory']

    if not execute_cmd(ctor_cmd, stages):
        FAILURES.append(f'{test["name"]} constructor in memory mode')


def test_applet(test: Dict[str, Any], cmd: List[str],
                entry_point: Dict[str, Any] = {}) -> None:
    dir_prefix: str = ''
    test_desc = test

    if entry_point:
        test_desc = entry_point
        cmd += ['--entry-point', entry_point['name']]
        dir_prefix = entry_point['name'] + '_'

    if 'resetIns' in test_desc:
        cmd += ['--reset-ins', test_desc['resetIns']]
    if 'cla' in test_desc:
        cmd += ['--cla', test_desc['cla']]

    stages = STAGES
    if 'failure' in test_desc:
        stages = stages[:STAGES.index(test_desc['failure']['stopAfter']) + 1]
        print('Test supports only:', stages)

    if ARGS.mode == 'memory':
        # test memory measurement in constructor
        test_ctor(test, cmd, dir_prefix, stages)

    for subtest in test_desc.get('subtests', []):
        sub_cmd = cmd.copy()
        test_dir = prepare_workdir(test, dir_prefix + subtest['executable'])

        sub_cmd += ['--work-dir', str(test_dir)]
        sub_cmd += ['--executable', subtest['executable']]

        if 'input' in subtest:
            sub_cmd += ['--data-regex', subtest['input']]
        else:
            input_file = Path(subtest['inputFile']).absolute()
            sub_cmd += ['--data-file', str(input_file)]

        if 'ins' in subtest:
            sub_cmd += ['--ins', subtest['ins']]
        if 'p1' in subtest:
            sub_cmd += ['--p1', subtest['p1']]
        if 'p2' in subtest:
            sub_cmd += ['--p2', subtest['p2']]

        print('Executing subtest', subtest['name'], 'in', ARGS.mode, 'mode')
        sub_cmd += ['--mode', ARGS.mode]

        if not execute_cmd(sub_cmd, stages):
            FAILURES.append(
                f'{test["name"]} {subtest["executable"]} in {ARGS.mode} mode')


def skip_test(test: Dict[str, Any]) -> Optional[str]:
    # skip tests disabled on given platform
    osName = platform.system()
    if osName.lower() in test and not test[osName.lower()]:
        return 'disabled on ' + osName

    # test requires older JCKit than possible
    if 'maxJckit' in test and ARGS.min_jckit is not None and \
            test['maxJckit'] < ARGS.min_jckit:
        return 'requires older JCKit than specified in --min-jckit'

    # test requires newer JCKit than possible
    if ARGS.max_jckit is not None and test['jckit'] > ARGS.max_jckit:
        return 'requires newer JCKit than specified in --max-jckit'

    # allow stats
    if ARGS.mode == 'stats':
        return None

    # test cannot be executed completely, unless --ci was passed
    if 'failure' in test or ('entryPoints' in test and
                             all('failure' in e for e in test['entryPoints'])):
        if not ARGS.ci:
            return 'failing test'

        # toplevel failure node
        if 'failure' in test and 'stopAfter' not in test['failure']:
            return 'empty test'

        # failure nodes in entryPoints
        if 'entryPoints' in test:
            f = map(lambda x: x['failure'], test['entryPoints'])
            if 'entryPoints' in test and all('stopAfter' not in e for e in f):
                return 'empty test'

    # time mode requires nonempty subtests
    if ARGS.mode == 'time' and not test.get('subtests', []) and not \
            any(e.get('subtests', []) for e in test.get('entryPoints', [])):
        return "no subtests"

    return None


def execute_test(test: Dict[str, Any]) -> None:
    reason = skip_test(test)
    if reason is not None:
        print(f'Skip test {test["name"]}: {reason}', colour=BOLD_YELLOW)
        SKIPS.append(f'{test["name"]}: {reason}')
        return

    print('Running test', test['name'])

    test['name'] = test['name'].replace(' ', '_')
    clone_git_repo(test['repo'], test['name'])

    jar = Path('../build/libs/JCProfilerNext-1.0-SNAPSHOT.jar').absolute()

    jckit_version = test['jckit']
    if ARGS.min_jckit is not None and ARGS.min_jckit > test['jckit']:
        jckit_version = ARGS.min_jckit
    jckit = Path(f'jckit/jc{jckit_version}_kit').absolute()

    cmd = ['java', '-jar', str(jar), '--jckit', str(jckit)]

    if 'etsi' in test:
        cmd += ['--jar', prepare_etsi(test['etsi'])]

    if 'gppro' in test:
        gppro = Path(f'gpapi/org.globalplatform-{test["gppro"]}' +
                     '/gpapi-globalplatform.jar').absolute()
        cmd += ['--jar', str(gppro)]

    if 'thoth' in test:
        cmd += ['--jar', prepare_thoth(test['thoth'])]

    if 'visa' in test:
        visa = Path('visa.jar').absolute()
        cmd += ['--jar', str(visa)]

    if ARGS.debug:
        cmd.append('--debug')

    if ARGS.mode == 'stats':
        get_stats(test, cmd)
        return

    cmd += ['--repeat-count', str(ARGS.repeat_count)]

    modify_repo(test)

    if not ARGS.card:
        cmd.append('--simulator')

    if 'entryPoints' not in test:
        test_applet(test, cmd)
        return

    for entry_point in test['entryPoints']:
        if 'failure' in entry_point and \
                (not ARGS.ci or 'stopAfter' not in entry_point['failure']):
            print('Skipping failing entry point', entry_point['name'],
                  colour=BOLD_YELLOW)
            continue

        print('Using', entry_point['name'], 'entry point')
        test_applet(test, cmd.copy(), entry_point)


def main() -> None:
    root = Path(__file__).parent.resolve()
    print('Test root:', root)
    os.chdir(root)

    rebuild_jar()

    with open('test-data.json') as f:
        data = json.load(f)

    # get API JAR files
    clone_git_repo(data['etsiRepo'], 'etsiapi', reclone=False)
    clone_git_repo(data['jckitRepo'], 'jckit', reclone=False)
    clone_git_repo(data['gpapiRepo'], 'gpapi', reclone=False)
    clone_git_repo(data['thothRepo'], 'thoth', reclone=False)
    download_file(data['visaJar'], 'visa.jar')

    tests = data['tests']
    if ARGS.filter:
        tests = [x for x in tests if any(y in x['name'] for y in ARGS.filter)]
        if not tests:
            raise ValueError(f'No tests match the {ARGS.filter} filter')

    for t in tests:
        execute_test(t)

    if SKIPS:
        print(len(SKIPS), 'skipped tests:')
        for skip in SKIPS:
            print(skip, colour=BOLD_YELLOW)

    if FAILURES:
        print(len(FAILURES), 'failed tests:')
        for failure in FAILURES:
            print(failure, colour=BOLD_RED)


def get_min_jckit() -> Optional[str]:
    # stats will not compile anything
    if ARGS.mode == 'stats':
        return None

    # older javac prints to stderr, newer to stdout
    ret = run(['javac', '-version'], check=True, stdout=PIPE, stderr=STDOUT)

    # get major version
    ver = int(ret.stdout.decode().split()[1].split('.')[0])

    # 8 and older
    if ver == 1:
        return None

    if 9 <= ver <= 11:
        return '303'

    # 12+
    return '310r20210706'


def parse_args(args: List[str] = []) -> None:
    global ARGS

    parser = ArgumentParser(description='JCProfilerNext test suite')

    parser.add_argument('-d', '--debug', action='store_true',
                        help='Execute in debug mode')

    parser.add_argument('--output-dir', default='.',
                        help='Directory where to store the test runs')

    parser.add_argument('--min-jckit',
                        help='Minimum JCKit version used during testing')
    parser.add_argument('--max-jckit',
                        help='Maximum JCKit version used during testing')

    parser.add_argument('--card', action='store_true',
                        help='Use a real card instead of a simulator')
    parser.add_argument('--repeat-count', type=int, default=100,
                        help='Number profiling rounds (default 100)')

    parser.add_argument('--mode', choices=['memory', 'time', 'stats'],
                        required=True, help='Execute tests for given mode')

    parser.add_argument('--ci', action='store_true',
                        help='Execute also partial tests (useful in CI)')

    parser.add_argument('filter', nargs='*',
                        help='List of applet names (see ./test_data.json)')

    ARGS = parser.parse_args(args if args else sys.argv[1:])

    if ARGS.card and ARGS.mode == 'stats':
        raise RuntimeError('Stats cannot be collected on a physical card!')

    # set minimum JCKit based on used JDK
    min_ver = get_min_jckit()
    if min_ver is not None and \
            (ARGS.min_jckit is None or ARGS.min_jckit < min_ver):
        print('Overriding minimum JCKit version to', min_ver)
        ARGS.min_jckit = min_ver


if __name__ == '__main__':
    parse_args()
    main()
