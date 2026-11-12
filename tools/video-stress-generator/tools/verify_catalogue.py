"""Explicit bounded 40-pattern export audit; retained hashes/reports, disposable media."""

import argparse
import ast
import importlib.util
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

import numpy as np
from PIL import Image, ImageDraw

from videostress import __version__
from videostress.color import planes, rgb_to_i420
from videostress.contract import digest, read_json, requirements, write_json
from videostress.font import text
from videostress.generate import estimate, generate, marker_plan
from videostress.marker import content_mask
from videostress.media import environment
from videostress.metrics import ALGORITHM, measure, summarize
from videostress.model import Timeline
from videostress.output import fresh_output
from videostress.patterns import PATTERNS, render
from videostress.provenance import code_identity
from videostress.validate import validate


def consumer_functions(path):
    tree = ast.parse(path.read_text(encoding="utf-8-sig"))
    functions = {"file_record","fixture_index","validate_fixture"}
    nodes = [n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in functions]
    if {n.name for n in nodes}!=functions:
        raise ValueError("Consumer pure contract functions changed; review the newer contract before adapting")
    namespace = {"Path":Path,"hashlib":hashlib,"re":re,"COLOR_FIELDS":("range","primaries","matrix","transfer","chroma_location")}
    layout_path = path.parent/"benchmark_layout.py"
    if layout_path.exists():
        layout = ast.parse(layout_path.read_text(encoding="utf-8-sig"))
        names = {"resolve","_aliases","_target","_key","_locator"}
        helpers = [n for n in layout.body if isinstance(n,ast.FunctionDef) and n.name in names]
        if {n.name for n in helpers}!=names:
            raise ValueError("Consumer read-only path resolver changed; review its contract")
        layout_namespace = {"Path":Path,"os":os,"json":json,"ROOT":path.resolve().parents[1],
                            "GROUPS":{"inputs","builds","catalog","runs"},"_CACHE":{}}
        # Since 6c712a6 the resolver maps declared evidence junctions through evidence_relocation.logical (pure, stdlib only).
        relocation_path = path.parent/"evidence_relocation.py"
        if relocation_path.exists():
            spec = importlib.util.spec_from_file_location("evidence_relocation",relocation_path)
            relocation = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(relocation)
            layout_namespace["logical"] = relocation.logical
        exec(compile(ast.Module(body=helpers,type_ignores=[]),str(layout_path),"exec"),layout_namespace)
        namespace["locate"] = layout_namespace["resolve"]
    exec(compile(ast.Module(body=nodes,type_ignores=[]),str(path),"exec"),namespace)
    return namespace


def control_evidence(name,mask):
    controls = {}
    for control,low,high in [("density",0.5,2.0),("speed",0.2,1.7),("detail",0.5,2.0),("entropy",0.0,1.0)]:
        runs = []
        for value in (low,high):
            rows,sha,previous = [],hashlib.sha256(),None
            for frame in range(6):
                rgb = render(name,160,96,7,frame,33,{control:value})
                data = rgb_to_i420(rgb)
                sha.update(data)
                y = planes(data,160,96)[0]
                rows.append(measure(y,previous,mask))
                previous = y.copy()
            runs.append({"value":value,"i420_sequence_sha256":sha.hexdigest(),"measurements":summarize(rows),
                         "changed_fraction":[r["changed_fraction"] for r in rows]})
        if runs[0]["i420_sequence_sha256"]==runs[1]["i420_sequence_sha256"]:
            raise ValueError(f"{name}/{control}: control had no pixel effect")
        if not any(abs(runs[0]["measurements"][m]["median"]-runs[1]["measurements"][m]["median"])>1e-5
                   for m in ("si","ti_including_cuts_seams","entropy_bits","mean_absolute_delta")):
            raise ValueError(f"{name}/{control}: control had no measured effect")
        controls[control] = runs
    return controls


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output",type=Path,required=True,help="Fresh retained evidence directory")
    p.add_argument("--consumer",type=Path,help="Read-only binder; execute only its three pure contract functions in memory")
    p.add_argument("--requirements",type=Path,help="Dated/newer requirements example, dry-run only")
    p.add_argument("--specification",type=Path)
    p.add_argument("--consumer-guide",type=Path)
    args = p.parse_args(argv)
    env = environment()
    contract = consumer_functions(args.consumer) if args.consumer else None
    inputs = {name:{"path":str(path.resolve()),"sha256":digest(path)} for name,path in
              (("consumer",args.consumer),("requirements",args.requirements),("specification",args.specification),("consumer_guide",args.consumer_guide)) if path}
    with fresh_output(args.output) as owned:
        write_json(owned.file("environment.json"),env)
        sheet = Image.new("RGB",(5*180,8*120),(12,15,20))
        draw = ImageDraw.Draw(sheet)
        records,signatures,controls = [],[],{}
        with tempfile.TemporaryDirectory(prefix="vsg-catalogue-") as temporary:
            for index,(name,pattern) in enumerate(PATTERNS.items()):
                config = {"schema_version":1,"seed":123,"modes":["160x96@7"],"lossless":True,
                          "timeline":[{"id":pattern["id"],"pattern":name,"frames":18}],
                          "audio":{"enabled":name=="visual_audio_sync"}}
                timeline = Timeline(config)
                output = Path(temporary)/pattern["id"]
                result = generate(timeline,output,env=env)
                verification = validate(output/"raw-fixtures.json")
                row = read_json(output/"raw-fixtures.json")["fixtures"][0]
                if contract:
                    key = (row["width"],row["height"],row["fps"])
                    actual = contract["fixture_index"](read_json(output/"raw-fixtures.json"))[key]
                    contract["validate_fixture"](actual,output)
                signature = digest(output/row["sidecars"]["rgb_reference"]["path"])
                signatures.append(signature)
                with (output/row["sidecars"]["frames"]["path"]).open(newline="",encoding="utf-8") as f:
                    frame_hashes = [{k:r[k] for k in ("file_frame","source_frame_id","rgb_sha256","i420_sha256")} for r in csv.DictReader(f)]
                records.append({"pattern":{k:v for k,v in pattern.items() if k!="render"},"config":config,
                    "unmarked_rgb_sequence_sha256":signature,"frame_hashes":frame_hashes,
                    "generation":result["fixtures"][0],"validation":verification["fixtures"][0],
                    "consumer_contract":"VERIFIED" if contract else "UNMEASURED"})
                cell_x,cell_y = index%5*180,index//5*120
                native = render(name,160,96,7,7,123)
                sheet.paste(Image.fromarray(native),(cell_x+10,cell_y+4))
                text(draw,(cell_x+4,cell_y+104),f"{pattern['id']} {name[:22]}",1,(235,235,235))
                if pattern["group"]=="complex":
                    controls[name] = control_evidence(name,content_mask(marker_plan(timeline,(160,96,7))))
                print(f"{pattern['id']} {name}: native export, exact reference and contract verified",flush=True)
        if len(records)!=40 or len(set(signatures))!=40:
            raise ValueError("Full catalogue did not have forty distinct verified pixel sequences")
        sheet.save(owned.file("catalogue.png"))
        if args.requirements:
            modes = [dict(zip(("width","height","fps"),m)) for m in sorted(requirements(args.requirements))]
            timeline = Timeline({"schema_version":1,"modes":modes,"timeline":[{"pattern":"native_checkerboard","frames":1}]})
            write_json(owned.file("requirements-dry-run.json"),estimate(timeline,env))
        for record in inputs.values():
            if digest(record["path"])!=record["sha256"]:
                raise ValueError("External read-only source changed during verification; inspect newer contract")
        write_json(owned.file("verification.json"),{"schema":"vsg-catalogue-verification-v1","status":"VERIFIED",
            "generator_version":__version__,"source_code":code_identity(),"read_only_sources":inputs,
            "pattern_count":len(records),"distinct_native_rgb_sequences":len(set(signatures)),"patterns":records,
            "complex_control_measurements":{"geometry":[160,96,7],"frames":6,"seed":33,"algorithm":ALGORITHM,"results":controls},
            "consumer_validation":"Pure binder functions verified hashes, sizes and sidecars in memory; binder main/app imports/devices were never run" if contract else "UNMEASURED",
            "disposable_media":"Only verified private temporary catalogue exports were removed; hashes/configs/reports retained",
            "natural_camera_comparison":"UNMEASURED: no camera-recorded source supplied",
            "app_streaming_performance":"UNMEASURED"})
    return 0


if __name__=="__main__":
    raise SystemExit(main())
