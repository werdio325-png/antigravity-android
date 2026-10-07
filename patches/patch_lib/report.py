"""Report dict schema and helpers shared by every patch group."""


def make_report(group_id, group, file, description, **extra):
    report = {
        "id": group_id,
        "group": group,
        "file": file,
        "description": description,
        "state": "error",
        "messages": [],
    }
    report.update(extra)
    return report


def hex_list(offsets):
    return [hex(o) for o in offsets]
