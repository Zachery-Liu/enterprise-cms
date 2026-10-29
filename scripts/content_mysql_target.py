"""Shared guard for local, explicitly named content verification databases."""
import os
import re
import urllib.parse


def target():
    schema = os.environ["CMS_MYSQL_TEST_SCHEMA"]
    if not re.fullmatch(r"cms_a_verify_[0-9]{8}", schema):
        raise RuntimeError("Only a dedicated cms_a_verify_YYYYMMDD schema is allowed")
    url = os.environ["CMS_MYSQL_TEST_URL"]
    if not url.startswith("jdbc:mysql://"):
        raise RuntimeError("Expected an explicit MySQL JDBC URL")
    parsed = urllib.parse.urlsplit(url[5:])
    if (parsed.hostname != "127.0.0.1" or parsed.path != "/" + schema or parsed.username
            or parsed.password or parsed.fragment or parsed.port is None):
        raise RuntimeError("URL must select the exact dedicated schema on an explicit loopback port")
    return schema, parsed.port


def evidence_name(name):
    label = os.environ.get("CMS_VERIFY_LABEL", "")
    if label and not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*", label):
        raise RuntimeError("Invalid verification evidence label")
    return label + name
