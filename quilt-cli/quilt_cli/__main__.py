#!/usr/bin/env python3
# Copyright 2022, Seqera Labs
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Written by Harshil Patel <harshil.patel@seqera.io>
#            Ben Sherman <ben.sherman@seqera.io>
#            Graham Wright <graham.wright@seqera.io>

import argparse
import boto3
import logging
import os
import quilt3
import sys


def parse_args(args=None):
    parser = argparse.ArgumentParser(description='Command-line tool to manage Quilt packages')
    subparsers = parser.add_subparsers(dest='subparser_name')

    sp_push = subparsers.add_parser('push', help='Create or update a Quilt package and push it to a remote registry')
    sp_push.add_argument('path_file', help='File containing list of S3 paths')
    sp_push.add_argument('package_name', help='Name of package, in the USER/PKG format')
    sp_push.add_argument('--registry', help='Registry where to create the new package. Defaults to the default remote registry.')
    sp_push.add_argument('--dest', help='Where to copy the objects in the package')
    sp_push.add_argument('--message', help='The commit message for the new package')
    sp_push.add_argument('--meta', help='Sets package-level metadata. Format: A json string with keys in double quotes \'{"key": "value"}\'')
    sp_push.add_argument('--workflow', help='Workflow ID or empty string to skip workflow validation. If not specified, the default workflow will be used.')
    sp_push.add_argument('--force', help='Skip the parent top hash check and create a new revision even if your local state is behind the remote registry.', action='store_true')
    sp_push.add_argument('--log-level', help='The desired log level', choices=('CRITICAL', 'ERROR', 'WARNING', 'INFO', 'DEBUG'), default='WARNING')
    sp_push.add_argument('--aws-profile', help='The AWS profile to use (defined in ~/.aws/credentials), uses the default profile if not specified')

    return parser.parse_args(args)


def s3_list_directories(s3_client, bucket_name='', prefix=''):
    '''List all 'directories' in a parent 'directory' (i.e. bucket prefix) of an S3 bucket.

    :param s3_client: S3 client
    :param bucket_name: S3 bucket
    :param prefix: S3 path prefix
    '''
    logging.info(f'Listing directories in s3://{bucket_name}/{prefix}')

    paginator = s3_client.get_paginator('list_objects_v2')
    pages = paginator.paginate(Bucket=bucket_name, Prefix=prefix, Delimiter='/')

    for page_idx, page in enumerate(pages):
        logging.info(f'  Page: {page_idx}, Prefix: {prefix}')

        for content in page.get('CommonPrefixes', []):
            yield content.get('Prefix')


def s3_classify_paths(paths, aws_profile=None):
    '''Determine whether each path is an S3 prefix (i.e. directory) or an object (i.e. file).

    Assumptions:
      - Based on object-naming rules here https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html, assume there is NO '/' in filename.
      - An S3 directory-like entity need not contain a file in order to be included in CommonPrefixes (confirmed with test).
      - A directory and a file with the same name can't exist in the same directory.

    Examples:
      - s3://seqera-quilt/genomes/ecoli/Bowtie2Index  : directory
      - s3://seqera-quilt/genomes/ecoli/Bowtie2Index/ : directory
      - s3://seqera-quilt/genomes/ecoli/DOCKERFILE    : file
      - s3://seqera-quilt/genomes/ecoli/example.txt   : file

    :param paths: list of S3 paths
    :param aws_profile: AWS credentials profile 
    '''
    # Initialize S3 client
    if aws_profile:
        session = boto3.session.Session(profile_name=aws_profile)
        s3_client = session.client('s3')
    else:
        s3_client = boto3.client('s3')

    # Classify each S3 path as a directory or file
    dirs = set()
    cache = set()

    for path in paths:
        # Extract bucket name and object path from S3 path
        bucket_name, object_path = path.replace('s3://', '').split('/', maxsplit=1)

        # Extract parent directory from object path
        if object_path.endswith('/'):
            object_path = object_path[:-1]

        prefix, _ = object_path.rsplit('/', maxsplit=1)
        prefix = prefix + '/'

        # Normalize object path so that it can be compared to CommonPrefixes
        if not object_path.endswith('/'):
            object_path = object_path + '/'

        # Check if object path was already confirmed as directory
        if object_path in cache:
            logging.info(f'Path `{path}` already confirmed as directory')
            dirs.add(path)

        # Skip if parent directory has already been queried
        elif prefix in cache:
            logging.info(f'Parent directory `s3://{bucket_name}/{prefix}` already queried')

        # Otherwise, query parent directory and update cache
        else:
            logging.info(f'Path `{path}` and parent directory were not found in cache')

            # Add parent directory and child directories to cache
            cache.add(prefix)
            cache = cache.union(s3_list_directories(s3_client, bucket_name, prefix))

            # Check if object path is a directory
            if object_path in cache:
                logging.info(f'Path `{path}` is a directory')
                dirs.add(path)

    # Normalzie directory paths
    dirs = [d + '/' if not d.endswith('/') else d for d in dirs]

    return dirs


def quilt_push(
    paths,
    package_name,
    registry=None,
    dest=None,
    message=None,
    meta=None,
    force=False,
    aws_profile=None):
    '''
    Create or edit a Quilt package with a list of S3 paths and push it to a remote registry.

    :param paths:
    :param package_name:
    :param registry:
    :param dest:
    :param message:
    :param meta:
    :param force:
    :param aws_profile:
    '''

    # Make sure credentials are properly set for the quilt package (confer: https://docs.quiltdata.com/api-reference/cli)
    if aws_profile:
        logging.info(f'Using AWS profile: {aws_profile}')
        os.environ['AWS_DEFAULT_PROFILE'] = aws_profile

    # Determine which paths are directories
    dirs = s3_classify_paths(paths, aws_profile=aws_profile)

    # Download quilt package if it already exists
    if package_name in set(quilt3.list_packages(registry)) and not force:
        quilt3.Package.install(package_name, registry=registry)
        package = quilt3.Package.browse(package_name, registry=registry)

    # Otherwise create a new quilt package
    else:
        package = quilt3.Package()

    # Add each path to quilt package
    for path in paths:
        quilt_path = os.path.basename(path.rstrip('/'))

        if path in dirs:
            package.set_dir(quilt_path, path)
        else:
            package.set(quilt_path, path)

    # Set package metadata if provided
    if meta:
        package.set_meta(meta)

    # Push Quilt package to S3
    package.push(
        package_name,
        registry=registry,
        dest=dest,
        message=message,
        selector_fn=lambda logical_key, package_entry: not package_entry.get().startswith('s3://'),
        force=force)


def main(args=None):
    # Parse command-line arguments
    args = parse_args(args)

    # Initialize logger
    logging.basicConfig(level=args.log_level, format='[%(levelname)s] %(message)s')

    # Load list of paths
    with open(args.path_file) as f:
        args.paths = [line.strip() for line in f]

    # Validate list of paths
    paths = [path for path in args.paths if path.startswith('s3://')]

    if len(paths) != len(args.paths):
        skipped_paths = set(args.paths) - set(paths)

        logging.warning('The following paths were skipped because they are not S3 paths:')
        for path in skipped_paths:
            logging.warning(f'  {path}')

    # Validate package name
    if len(args.package_name.split('/')) != 2:
        logging.error('Package name must be formatted as <user>/<pkg>')
        sys.exit(1)

    # route subcommand to function
    if args.subparser_name == 'push':
        quilt_push(
            paths,
            args.package_name,
            registry=args.registry,
            dest=args.dest,
            message=args.message,
            meta=args.meta,
            force=args.force,
            aws_profile=args.aws_profile)
