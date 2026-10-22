"""Offline real-image checks for 2.1 bt_log, without digests or device access."""
from pathlib import Path
import json

from validate_host import ROOT, invoke, image_dtbs, parse_fdt, parse_vendor, avb_blob

OUT = ROOT / 'analysis_lk/ota_next_20261022/bt_log_fix'


def tree(blob):
    return {path: {key: value['data'] for key, value in node['properties'].items()}
            for path, node in parse_fdt(blob).items()}


def main():
    OUT.mkdir(exist_ok=True)
    reports = []
    old = ROOT / 'extracted_20261010_234555'
    old_out = OUT / 'vendor_boot_oldstock_btlog.img'
    old_report = invoke(old / 'vendor_boot.img', old_out, old / 'scp.img', 134217728)
    reports.append({'case': 'old_stock_full_image', 'report': old_report, 'passed': True})

    new = ROOT / 'analysis_lk/ota_next_20261022/new_stock'
    candidate = OUT / 'vendor_boot_newota_btlog_candidate.img'
    assert candidate.stat().st_size == 134217728
    v2 = ROOT / 'analysis_lk/ota_next_20261022/app_images/main/patched_vendor_boot.img'
    repatched = OUT / 'vendor_boot_failed_a_btlog_repatch.img'
    repatch_report = invoke(v2, repatched, new / 'scp.img', 134217728)
    before, after, reprocessed = v2.read_bytes(), candidate.read_bytes(), repatched.read_bytes()
    assert after == reprocessed, 'fresh stock rebuild and failed V2 repair differ'
    a, b = parse_vendor(before), parse_vendor(after)
    assert a['config'] == b['config'] and avb_blob(before) == avb_blob(after)
    assert len(a['entries']) == len(b['entries'])
    for x, y in zip(a['entries'], b['entries']):
        assert x['data'] == y['data'] and x['record'] == y['record']
    before_trees, after_trees = list(image_dtbs(before)), list(image_dtbs(after))
    assert len(before_trees) == len(after_trees)
    for original, changed in zip(before_trees, after_trees):
        expected = tree(original)
        assert 'bt_log' not in expected['/chosen']
        expected['/chosen']['bt_log'] = bytes.fromhex('11100000')
        assert tree(changed) == expected
    assert a['dtb_offset'] == b['dtb_offset'] and a['table_offset'] == b['table_offset']
    # Outside the DTB allocation and its header size field, every byte stays equal.
    assert before[:2100] == after[:2100]
    assert before[2104:a['dtb_offset']] == after[2104:b['dtb_offset']]
    assert before[a['table_offset']:] == after[b['table_offset']:]
    reports.append({'case': 'new_stock_matches_V2_repair_only_bt_log_tree_change', 'report': repatch_report,
                    'all_fragments_bootconfig_AVB_unchanged': True,
                    'bytes_outside_DTB_and_header_length_unchanged': True, 'passed': True})

    second = OUT / 'vendor_boot_newota_btlog_second_pass.img'
    second_report = invoke(candidate, second, new / 'scp.img', 134217728)
    assert not second_report['changed'] and candidate.read_bytes() == second.read_bytes()
    assert all(not x['btLogAdded'] and not x['btLogChanged'] for x in second_report['dtbs'])
    reports.append({'case': 'new_stock_full_image_idempotence', 'report': second_report, 'passed': True})
    result = {'passed': True, 'device_access': False, 'digest_calculation': False,
              'candidate': str(candidate), 'cases': reports}
    (OUT / 'real_fixture_validation.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'passed': True, 'cases': len(reports), 'candidate': str(candidate)}))


if __name__ == '__main__':
    main()
