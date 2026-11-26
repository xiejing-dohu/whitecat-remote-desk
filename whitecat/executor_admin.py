"""Register a Whitecat execution Agent locally, without displaying its bearer credential."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3

import executor_registry
import server


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest="action",required=True)
    add=commands.add_parser("add")
    add.add_argument("--id",required=True)
    add.add_argument("--name",required=True)
    add.add_argument("--config-dir",type=Path)
    add.add_argument("--server",default="http://127.0.0.1:18742")
    commands.add_parser("list")
    args=parser.parse_args()
    if not server.DB.is_file():parser.error("请先启动此实例的白猫服务；WHITECAT_STATE 指向服务端数据目录。")
    try:
        with closing(sqlite3.connect(server.DB,timeout=10)) as db,db:
            if args.action=="list":result={"executors":executor_registry.catalog(db)}
            else:
                directory=args.config_dir if args.config_dir else server.STATE/"executors"/args.id
                result=executor_registry.enroll(db,args.id,args.name,directory,args.server)
    except (executor_registry.EnrollmentError,OSError,sqlite3.Error) as exc:
        parser.exit(1,str(exc)+"\n")
    print(json.dumps(result,ensure_ascii=False))


if __name__=="__main__":
    main()
