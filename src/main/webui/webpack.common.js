const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const TsconfigPathsPlugin = require('tsconfig-paths-webpack-plugin');
const ForkTsCheckerPlugin = require('fork-ts-checker-webpack-plugin');
const PreloadWebpackPlugin = require('@vue/preload-webpack-plugin');

const BG_IMAGES_DIRNAME = 'bgimages';
const ASSET_PATH = process.env.ASSET_PATH || '/';

module.exports = (env) => {
  return {
    context: __dirname,
    entry: {
      app: path.resolve(__dirname, 'src', 'index.tsx')
    },
    plugins: [
      new ForkTsCheckerPlugin(),
      new HtmlWebpackPlugin({
        template: path.resolve(__dirname, 'src', 'index.html'),
        favicon: './src/app/assets/favicon.ico',
      }),
      new PreloadWebpackPlugin({
        rel: 'prefetch',
        include: [/\.js$/], // lazy-load chunks to prefetch
        // exlude initial chunk, npm chunks
        fileBlacklist: [/^(app|npm)(\.[\w-]+)+\.bundle\.js$/]
      })
    ],
    // https://medium.com/hackernoon/the-100-correct-way-to-split-your-chunks-with-webpack-f8a9df5b7758
    optimization: {
      runtimeChunk: 'single', //  create a runtime file to be shared for all generated chunks
      moduleIds: 'deterministic', // avoid changing module.id of vendor bundles: https://webpack.js.org/guides/caching/#module-identifiers
      splitChunks: {
        chunks: 'all', // https://webpack.js.org/plugins/split-chunks-plugin/#splitchunkschunks
        maxInitialRequests: Infinity,
        minSize: 0,
        cacheGroups: {
          vendor: {
            test: /[\\/]node_modules[\\/]/,
            name(module) {
              // get the name. E.g. node_modules/packageName/not/this/part.js
              // or node_modules/packageName
              const matches = module.context.match(/[\\/]node_modules[\\/](.*?)([\\/]|$)/);
              if (!Array.isArray(matches)) {
                return null;
              }
              if (matches.length < 2) {
                return null;
              }
              const packageName = matches[1];

              // npm package names are URL-safe, but some servers don't like @ symbols
              return `npm.${packageName.replace('@', '')}`;
            },
          },
        },
      },
    },
    module: {
      rules: [
        {
          test: /\.(tsx|ts|jsx)?$/,
          exclude: /node_modules/,
          use: [
            {
              loader: 'ts-loader',
              options: {
                transpileOnly: true,
                experimentalWatchApi: true,
              }
            }
          ]
        },
        {
          test: /\.(svg|ttf|eot|woff|woff2)$/,
          // only process modules with this loader
          // if they live under a 'fonts' or 'pficon' directory
          include: [
            path.resolve(__dirname, 'node_modules/patternfly/dist/fonts'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-core/dist/styles/assets/fonts'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-core/dist/styles/assets/pficon'),
            path.resolve(__dirname, 'node_modules/@patternfly/patternfly/assets/fonts'),
            path.resolve(__dirname, 'node_modules/@patternfly/patternfly/assets/pficon')
          ],
          type: 'asset',
          parser: {
            dataUrlCondition: {
              maxSize: 5000 // Limit at 50kb. larger files emited into separate files
            }
          },
          generator: {
            filename: 'fonts/[name][ext]'
          }
        },
        {
          test: /\.svg$/,
          include: input => input.indexOf('background-filter.svg') > 1,
          type: 'asset',
          parser: {
            dataUrlCondition: {
              maxSize: 5000
            }
          },
          generator: {
            filename: 'svgs/[name][ext]'
          }
        },
        {
          test: /\.svg$/,
          // only process SVG modules with this loader if they live under a 'bgimages' directory
          // this is primarily useful when applying a CSS background using an SVG
          include: input => input.indexOf(BG_IMAGES_DIRNAME) > -1,
          use: {
            loader: 'svg-url-loader',
            options: {}
          }
        },
        {
          test: /\.svg$/,
          // only process SVG modules with this loader when they don't live under a 'bgimages',
          // 'fonts', or 'pficon' directory, those are handled with other loaders
          include: input => (
            (input.indexOf(BG_IMAGES_DIRNAME) === -1) &&
            (input.indexOf('fonts') === -1) &&
            (input.indexOf('background-filter') === -1) &&
            (input.indexOf('pficon') === -1)
          ),
          exclude: [
            path.resolve(__dirname, 'src/app/assets')
          ],
          type: 'asset/source',
        },
        {
          test: /\.(jpg|jpeg|png|gif|svg)$/i,
          include: [
            path.resolve(__dirname, 'src'),
            path.resolve(__dirname, 'node_modules/patternfly'),
            path.resolve(__dirname, 'node_modules/@patternfly/patternfly/assets/images'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-styles/css/assets/images'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-core/dist/styles/assets/images'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-core/node_modules/@patternfly/react-styles/css/assets/images'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-table/node_modules/@patternfly/react-styles/css/assets/images'),
            path.resolve(__dirname, 'node_modules/@patternfly/react-inline-edit-extension/node_modules/@patternfly/react-styles/css/assets/images')
          ],
          type: 'asset',
          parser: {
            dataUrlCondition: {
              maxSize: 5000
            }
          },
          generator: {
            filename: 'images/[name][ext]'
          },
        }
      ]
    },
    output: {
      filename: '[name].[contenthash].bundle.js',
      chunkFilename: '[id].[contenthash].bundle.js', // lazy-load modules
      hashFunction: "xxhash64",
      path: path.resolve(__dirname, 'dist'),
      publicPath: ASSET_PATH,
      clean: true
    },
    resolve: {
      extensions: ['.ts', '.tsx', '.js', '.jsx'],
      plugins: [
        new TsconfigPathsPlugin({
          configFile: path.resolve(__dirname, './tsconfig.json')
        })
      ],
      symlinks: false,
      cacheWithContext: false
    },
  };
}
