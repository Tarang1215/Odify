from __future__ import annotations

import configparser
import csv
from io import BytesIO
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from xml.etree import ElementTree
from zipfile import ZipFile


SPREADSHEET_ID = os.environ.get('ODIFY_MASTER_SHEET_ID', '')
SHEET_NAME = 'MASTER'
NAMESPACE = {'sheet': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}


def drive_request(resource: str, *, parameters: dict | None = None) -> bytes:
    config_path = os.environ.get('RCLONE_CONFIG')
    if not config_path:
        raise RuntimeError('Run the Odify launcher to select the private Drive configuration.')
    configuration = configparser.ConfigParser(interpolation=None)
    configuration.read(config_path, encoding='utf-8-sig')
    token = json.loads(configuration.get('mydrive', 'token'))
    url = 'https://www.googleapis.com/drive/v3/' + resource
    if parameters:
        url += '?' + urlencode(parameters)
    request = Request(url, headers={'Authorization': 'Bearer ' + token['access_token']})
    try:
        with urlopen(request, timeout=60) as response:
            data = response.read(12 * 1024 * 1024 + 1)
        if len(data) > 12 * 1024 * 1024:
            raise RuntimeError('Google export exceeded the allowed download size.')
        return data
    except HTTPError as error:
        raise RuntimeError(f'Drive request failed with HTTP {error.code}; credentials were not logged.') from None


def workbook_rows(data: bytes, sheet_name: str = SHEET_NAME) -> list[list[str]]:
    with ZipFile(BytesIO(data)) as archive:
        if sum(entry.file_size for entry in archive.infolist()) > 80 * 1024 * 1024:
            raise ValueError('Workbook expands beyond the allowed size.')
        workbook = ElementTree.fromstring(archive.read('xl/workbook.xml'))
        relationships = ElementTree.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
        targets = {entry.attrib['Id']: entry.attrib['Target'] for entry in relationships}
        relationship_attribute = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'
        selected = next((entry for entry in workbook.findall('sheet:sheets/sheet:sheet', NAMESPACE)
                         if entry.attrib['name'] == sheet_name), None)
        if selected is None:
            raise ValueError(f'Workbook has no {sheet_name} tab.')
        target = targets[selected.attrib[relationship_attribute]]
        sheet_path = posixpath.normpath(posixpath.join('xl', target)) if not target.startswith('/') else target.lstrip('/')
        if not sheet_path.startswith('xl/') or '..' in sheet_path.split('/'):
            raise ValueError('Invalid workbook sheet relationship.')
        shared = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            strings = ElementTree.fromstring(archive.read('xl/sharedStrings.xml'))
            shared = [''.join(entry.itertext()) for entry in strings]
        sheet = ElementTree.fromstring(archive.read(sheet_path))
        result = []
        for row in sheet.findall('sheet:sheetData/sheet:row', NAMESPACE):
            values = []
            for cell in row.findall('sheet:c', NAMESPACE):
                letters = ''.join(character for character in cell.attrib['r'] if character.isalpha())
                column = 0
                for character in letters:
                    column = column * 26 + ord(character.upper()) - ord('A') + 1
                while len(values) < column:
                    values.append('')
                value = cell.find('sheet:v', NAMESPACE)
                text = '' if value is None else (value.text or '')
                if cell.attrib.get('t') == 's':
                    text = shared[int(text)]
                elif cell.attrib.get('t') == 'inlineStr':
                    inline = cell.find('sheet:is', NAMESPACE)
                    text = '' if inline is None else ''.join(inline.itertext())
                values[column - 1] = text
            if any(value.strip() for value in values):
                result.append(values)
        return result


def refresh_master(destination: Path) -> dict:
    if not SPREADSHEET_ID or not re.fullmatch(r'[A-Za-z0-9_-]{10,200}', SPREADSHEET_ID):
        raise RuntimeError('Set ODIFY_MASTER_SHEET_ID privately or pass an existing --csv snapshot.')
    probe = subprocess.run(['rclone', 'lsf', 'mydrive:', '--dirs-only', '--max-depth', '1'],
                           capture_output=True, text=True, timeout=90)
    if probe.returncode:
        raise RuntimeError('Drive authentication check failed. Run install_and_connect.cmd.')
    metadata = json.loads(drive_request('files/' + SPREADSHEET_ID,
                                       parameters={'fields': 'id,name,mimeType,modifiedTime'}))
    if metadata['mimeType'] != 'application/vnd.google-apps.spreadsheet':
        raise ValueError('Configured source is not a Google spreadsheet.')
    data = drive_request('files/' + SPREADSHEET_ID + '/export', parameters={
        'mimeType': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'})
    rows = workbook_rows(data)
    if not rows or rows[0][:2] != ['Track ID', '연도']:
        raise ValueError('MASTER headers do not match the collection schema.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8-sig', newline='') as handle:
        csv.writer(handle).writerows(rows)
    temporary.replace(destination)
    return metadata
