"""跨平台生成 tar.gz（Windows 无 tar 时使用），并修正 .sh 可执行位。"""
import os
import sys
import tarfile


def main():
    if len(sys.argv) != 4:
        print('usage: make_tar.py <stage_root> <arc_name> <out_tgz>')
        return 2
    stage_root, arc, out = sys.argv[1], sys.argv[2], sys.argv[3]
    src = os.path.join(stage_root, arc)
    if not os.path.isdir(src):
        print('missing stage dir:', src)
        return 1
    if os.path.exists(out):
        os.remove(out)

    with tarfile.open(out, 'w:gz') as tf:
        root = tarfile.TarInfo(arc)
        root.type = tarfile.DIRTYPE
        root.mode = 0o755
        tf.addfile(root)

        for cur, _dirs, files in os.walk(src):
            rel_dir = os.path.relpath(cur, stage_root).replace('\\', '/')
            if rel_dir != arc:
                di = tarfile.TarInfo(rel_dir)
                di.type = tarfile.DIRTYPE
                di.mode = 0o755
                tf.addfile(di)
            for name in files:
                full = os.path.join(cur, name)
                rel = os.path.relpath(full, stage_root).replace('\\', '/')
                ti = tf.gettarinfo(full, arcname=rel)
                ti.mode = 0o755 if name.endswith('.sh') else 0o644
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = ''
                with open(full, 'rb') as fh:
                    tf.addfile(ti, fh)

    print('created:', out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
